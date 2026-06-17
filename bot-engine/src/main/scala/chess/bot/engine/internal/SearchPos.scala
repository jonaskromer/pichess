package chess.bot.engine.internal

import chess.model.board.{CastlingRights, MoveInt, Position, PositionView}
import chess.model.piece.Color
import chess.model.rules.MoveValidator

/** Search-internal MUTABLE position for copy-make: a [[MutableBoard]] plus the
  * position metadata that move-gen / Zobrist / eval read ([[PositionView]]).
  * Reused per ply via `SearchBufs.positions`, so descending into a child
  * allocates nothing — the per-node immutable `GameState` (~18 %) + the apply
  * `Some` (~13 %) both vanish.
  *
  * `enPassantTarget` / `castlingRights` are kept as the immutable
  * `Option[Position]` / `CastlingRights` the readers already consume: they only
  * churn on a double-push / a rights change (rare), so reads stay
  * allocation-free while matching `GameState` bit-for-bit.
  *
  * The copy-make apply ([[copyMakeInto]]) is a faithful in-place port of
  * `Game.applyMoveCoreSync` + `buildPostMoveState`: same board result, same
  * metadata, same legality verdict — verified by `PerftSpec` (the published
  * perft counts) and the determinism / incremental-NNUE golden specs.
  */
private[engine] final class SearchPos extends PositionView:

  val board: MutableBoard = new MutableBoard
  var activeColor: Color = Color.White
  var enPassantTarget: Option[Position] = None
  var castlingRights: CastlingRights = CastlingRights()
  var halfmoveClock: Int = 0

  /** Load this position from an immutable one (the search root, ply 0). `s` is
    * typically a `GameState`; read through [[PositionView]].
    */
  def setFrom(s: PositionView): Unit =
    board.copyFrom(s.board)
    activeColor = s.activeColor
    enPassantTarget = s.enPassantTarget
    castlingRights = s.castlingRights
    halfmoveClock = s.halfmoveClock

  /** Copy-make: copy `this` into `child`, apply `moveInt` in place (captures,
    * castling rook hop, en passant, promotion incl. under-promotion), flip the
    * side to move, and update en-passant target / castling rights / halfmove
    * clock. Returns the move's LEGALITY as a Boolean — `true` iff the move does
    * not leave the mover's king in check (no `Option`/`null`/sentinel). `child`
    * MUST be a different instance than `this`.
    *
    * Equivalent to `Game.applyMoveCoreSync(this, decode(moveInt)) .isDefined`,
    * leaving the post-move position in `child`.
    */
  def copyMakeInto(child: SearchPos, moveInt: Int): Boolean =
    val white = activeColor == Color.White
    val fromIdx = MoveInt.fromIdx(moveInt)
    val toIdx = MoveInt.toIdx(moveInt)
    val promo = MoveInt.promo(moveInt)
    val b = board
    val fromMask = 1L << fromIdx
    val toMask = 1L << toIdx

    // Moving piece type, as a 0..5 P,N,B,R,Q,K index (own internal
    // convention — independent of PieceType.ordinal).
    val typeOrd =
      if white then
        if (b.pawnsW.raw & fromMask) != 0L then 0
        else if (b.knightsW.raw & fromMask) != 0L then 1
        else if (b.bishopsW.raw & fromMask) != 0L then 2
        else if (b.rooksW.raw & fromMask) != 0L then 3
        else if (b.queensW.raw & fromMask) != 0L then 4
        else 5
      else if (b.pawnsB.raw & fromMask) != 0L then 0
      else if (b.knightsB.raw & fromMask) != 0L then 1
      else if (b.bishopsB.raw & fromMask) != 0L then 2
      else if (b.rooksB.raw & fromMask) != 0L then 3
      else if (b.queensB.raw & fromMask) != 0L then 4
      else 5

    val isPawn = typeOrd == 0
    val isKing = typeOrd == 5
    val fromFile = fromIdx & 7
    val toFile = toIdx & 7
    val epIdx = enPassantTarget match
      case Some(p) => p.squareIdx
      case None    => -1
    val isEp = isPawn && toIdx == epIdx && toFile != fromFile
    val isCastling = isKing && math.abs(toFile - fromFile) == 2
    val wasCapture = (b.occupancy.raw & toMask) != 0L || isEp

    // Post-promotion placed piece (promo overrides the pawn).
    val placedOrd =
      if promo == MoveInt.NoPromotion then typeOrd
      else
        promo match
          case MoveInt.PromoQueen  => 4
          case MoveInt.PromoRook   => 3
          case MoveInt.PromoBishop => 2
          case _                   => 1 // PromoKnight
    val placedIdx = if white then placedOrd else 6 + placedOrd

    // ── Board ──────────────────────────────────────────────────────
    val cb = child.board
    cb.copyFrom(b)
    cb.movePiece(fromIdx, toIdx, placedIdx)
    if isCastling then
      val rankBase = fromIdx & ~7 // rank * 8
      val kingSide = toFile > fromFile
      val rookFrom = rankBase + (if kingSide then 7 else 0)
      val rookTo = rankBase + (if kingSide then 5 else 3)
      cb.movePiece(rookFrom, rookTo, if white then 3 else 9)
    else if isEp then cb.clearSquare(if white then toIdx - 8 else toIdx + 8)
    cb.recomputeAggregates()

    // ── Metadata ───────────────────────────────────────────────────
    child.activeColor = if white then Color.Black else Color.White
    val fromRank = fromIdx >>> 3
    val toRank = toIdx >>> 3
    child.enPassantTarget =
      if isPawn && math.abs(toRank - fromRank) == 2 then
        Some(Position(('a' + fromFile).toChar, (fromRank + toRank) / 2 + 1))
      else None
    child.castlingRights = nextCastlingRights(fromIdx, toIdx, typeOrd, white)
    child.halfmoveClock = if isPawn || wasCapture then 0 else halfmoveClock + 1

    // Legality: the mover's own king must not be left in check.
    !MoveValidator.isInCheck(cb, if white then Color.White else Color.Black)

  /** Copy `this` into `child` for a NULL move: same board, flipped side,
    * cleared en-passant target, +1 halfmove clock (castling rights unchanged).
    * Mirrors the old `nullMoveState`; the board is untouched so the maintained
    * NNUE accumulator stays valid.
    */
  def copyNullMoveInto(child: SearchPos): Unit =
    child.board.copyFrom(board)
    child.activeColor =
      if activeColor == Color.White then Color.Black else Color.White
    child.enPassantTarget = None
    child.castlingRights = castlingRights
    child.halfmoveClock = halfmoveClock + 1

  /** Castling-rights after a move — port of `Game.updatedCastlingRights` to
    * LERF indices. Returns the SAME `CastlingRights` instance when no flag
    * changes (the common case) so quiet moves don't allocate.
    */
  private def nextCastlingRights(
      fromIdx: Int,
      toIdx: Int,
      typeOrd: Int,
      white: Boolean
  ): CastlingRights =
    val cr = castlingRights
    var wK = cr.whiteKingSide
    var wQ = cr.whiteQueenSide
    var bK = cr.blackKingSide
    var bQ = cr.blackQueenSide

    // Revoke based on the piece that moved (king → both; rook off its
    // home square → that side).
    val fromRank = fromIdx >>> 3
    val fromFile = fromIdx & 7
    if typeOrd == 5 then
      if white then { wK = false; wQ = false }
      else { bK = false; bQ = false }
    else if typeOrd == 3 then
      if white && fromRank == 0 then
        if fromFile == 7 then wK = false else if fromFile == 0 then wQ = false
      else if !white && fromRank == 7 then
        if fromFile == 7 then bK = false else if fromFile == 0 then bQ = false

    // Revoke when a rook is captured on its starting square (by `to`).
    val toRank = toIdx >>> 3
    val toFile = toIdx & 7
    if toRank == 0 then
      if toFile == 7 then wK = false else if toFile == 0 then wQ = false
    else if toRank == 7 then
      if toFile == 7 then bK = false else if toFile == 0 then bQ = false

    if wK == cr.whiteKingSide && wQ == cr.whiteQueenSide &&
      bK == cr.blackKingSide && bQ == cr.blackQueenSide
    then cr
    else CastlingRights(wK, wQ, bK, bQ)

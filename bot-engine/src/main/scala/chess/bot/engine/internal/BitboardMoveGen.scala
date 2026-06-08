package chess.bot.engine.internal

import chess.model.board.{GameState, Move, MoveInt, Position}
import chess.model.piece.{Color, PieceType}
import chess.model.rules.{BitboardAttacks, Game}

/** Zero-allocation-on-generation bitboard move generator.
  *
  * Replaces the `MoveValidator.legalMovesFromSync` /
  * `legalDestinationsIndexSync` path's `candidateMoves` → `List.flatMap` →
  * `toList` → `distinct` → `Map.newBuilder` allocation chain
  * (which the profile flagged as ~24% of CPU spent in GC) with a
  * direct walk over piece bitboards.
  *
  * Algorithm per piece type:
  *   * pawns — forward push (single + double), captures, en passant,
  *     promotion (queens only, like the existing generator)
  *   * knight, bishop, rook, queen, king — destination bitboard via
  *     `BitboardAttacks.*` AND'd with `~ownOcc`
  *   * king castling — the two canonical squares (g/c-file); the
  *     legality check rejects invalid attempts
  *
  * Legality is still validated by `Game.applyMoveCoreSync` per
  * candidate, which allocates one `Move` + one `BoardState`. That
  * deeper optimisation (true bitboard legality via post-move
  * attacker check) is a follow-up; this version captures the
  * generation-side savings.
  *
  * Output: writes encoded `MoveInt`s into the supplied `Int`
  * buffers (separately for captures and quiets), returns
  * `(captureCount, quietCount)`. Same contract as
  * `RulesAdapter.fillCapturesAndQuiets`. */
object BitboardMoveGen:

  /** Fill the supplied buffers with all legal moves from `state`,
    * partitioned into captures and quiets. */
  def fillCapturesAndQuiets(
      state: GameState,
      capBuf: Array[Int],
      quietBuf: Array[Int],
  ): (Int, Int) =
    val board = state.board
    val white = state.activeColor == Color.White

    val pawns =
      if white then board.pawnsW.raw else board.pawnsB.raw
    val knights =
      if white then board.knightsW.raw else board.knightsB.raw
    val bishops =
      if white then board.bishopsW.raw else board.bishopsB.raw
    val rooks =
      if white then board.rooksW.raw else board.rooksB.raw
    val queens =
      if white then board.queensW.raw else board.queensB.raw
    val king =
      if white then board.kingW.raw else board.kingB.raw
    val ownOcc =
      if white then board.whitePieces.raw else board.blackPieces.raw
    val enemyOcc =
      if white then board.blackPieces.raw else board.whitePieces.raw
    val occ    = board.occupancy.raw
    val notOwn = ~ownOcc

    val epToIdx: Int = state.enPassantTarget.fold(-1)(_.squareIdx)
    val epMask: Long = if epToIdx >= 0 then 1L << epToIdx else 0L

    // Mutable counters for buffer writes. JVM treats these as
    // stack locals; no allocation.
    var nc = 0
    var nq = 0

    // Apply legality check + encode + write. `Move` + `BoardState`
    // allocation still happens here (inside applyMoveCoreSync);
    // killing those is the next iteration after this commit.
    inline def tryEmit(from: Int, to: Int, promo: Int, capture: Boolean): Unit =
      val fromPos = Position(('a' + from % 8).toChar, from / 8 + 1)
      val toPos   = Position(('a' + to % 8).toChar, to / 8 + 1)
      val promoOpt =
        if promo == MoveInt.PromoQueen then Some(PieceType.Queen)
        else None
      val mv = Move(fromPos, toPos, promoOpt)
      Game.applyMoveCoreSync(state, mv) match
        case Some(_) =>
          val encoded = MoveInt.encode(from, to, promo)
          if capture then
            capBuf(nc) = encoded
            nc += 1
          else
            quietBuf(nq) = encoded
            nq += 1
        case None => ()

    // ── Pawn moves ────────────────────────────────────────────
    val pushStep   = if white then 8 else -8
    val startRank  = if white then 1 else 6
    val promoRank  = if white then 7 else 0
    var p = pawns
    while p != 0L do
      val from = java.lang.Long.numberOfTrailingZeros(p)
      p &= p - 1L
      val pushTo = from + pushStep
      // Forward push (only if destination empty)
      if pushTo >= 0 && pushTo < 64 && (occ & (1L << pushTo)) == 0L then
        val pushRank = pushTo >>> 3
        if pushRank == promoRank then
          tryEmit(from, pushTo, MoveInt.PromoQueen, capture = false)
        else
          tryEmit(from, pushTo, MoveInt.NoPromotion, capture = false)
          // Double push from starting rank
          if (from >>> 3) == startRank then
            val pushTo2 = pushTo + pushStep
            if (occ & (1L << pushTo2)) == 0L then
              tryEmit(from, pushTo2, MoveInt.NoPromotion, capture = false)
      // Diagonal captures + en passant
      val attacks   = pawnAttacks(from, white)
      val captureTo = attacks & (enemyOcc | epMask)
      var cb = captureTo
      while cb != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(cb)
        cb &= cb - 1L
        val toRank = to >>> 3
        // Captures landing on enemy pieces are real captures.
        // En passant captures (to == epToIdx) also land on an
        // "empty" square but are still semantically captures.
        if toRank == promoRank then
          tryEmit(from, to, MoveInt.PromoQueen, capture = true)
        else
          tryEmit(from, to, MoveInt.NoPromotion, capture = true)

    // ── Knight moves ──────────────────────────────────────────
    var n = knights
    while n != 0L do
      val from = java.lang.Long.numberOfTrailingZeros(n)
      n &= n - 1L
      var dests = BitboardAttacks.knightAttacks(from) & notOwn
      while dests != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(dests)
        dests &= dests - 1L
        val capture = (enemyOcc & (1L << to)) != 0L
        tryEmit(from, to, MoveInt.NoPromotion, capture)

    // ── Bishop + queen diagonal moves ─────────────────────────
    var diag = bishops | queens
    while diag != 0L do
      val from = java.lang.Long.numberOfTrailingZeros(diag)
      diag &= diag - 1L
      var dests = BitboardAttacks.bishopAttacks(from, occ) & notOwn
      while dests != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(dests)
        dests &= dests - 1L
        val capture = (enemyOcc & (1L << to)) != 0L
        tryEmit(from, to, MoveInt.NoPromotion, capture)

    // ── Rook + queen orthogonal moves ─────────────────────────
    var ortho = rooks | queens
    while ortho != 0L do
      val from = java.lang.Long.numberOfTrailingZeros(ortho)
      ortho &= ortho - 1L
      var dests = BitboardAttacks.rookAttacks(from, occ) & notOwn
      while dests != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(dests)
        dests &= dests - 1L
        val capture = (enemyOcc & (1L << to)) != 0L
        tryEmit(from, to, MoveInt.NoPromotion, capture)

    // ── King moves + castling ─────────────────────────────────
    if king != 0L then
      val kFrom = java.lang.Long.numberOfTrailingZeros(king)
      var kDests = BitboardAttacks.kingAttacks(kFrom) & notOwn
      while kDests != 0L do
        val to = java.lang.Long.numberOfTrailingZeros(kDests)
        kDests &= kDests - 1L
        val capture = (enemyOcc & (1L << to)) != 0L
        tryEmit(kFrom, to, MoveInt.NoPromotion, capture)
      // Castling — emit candidates from e1/e8; applyMoveCoreSync
      // rejects invalid castles (rights consumed, in check,
      // through check, blocked squares).
      val rank = if white then 0 else 7
      val kingStart = 4 + rank * 8
      if kFrom == kingStart then
        tryEmit(kFrom, 6 + rank * 8, MoveInt.NoPromotion, capture = false)
        tryEmit(kFrom, 2 + rank * 8, MoveInt.NoPromotion, capture = false)

    (nc, nq)

  /** Bitboard of squares a pawn at `from` attacks (capture squares
    * only, no forward push). Branchless on column edges via the
    * mask checks. */
  private inline def pawnAttacks(from: Int, white: Boolean): Long =
    val col = from % 8
    var bb = 0L
    if white then
      if col > 0 then bb |= 1L << (from + 7)
      if col < 7 then bb |= 1L << (from + 9)
    else
      if col > 0 then bb |= 1L << (from - 9)
      if col < 7 then bb |= 1L << (from - 7)
    bb

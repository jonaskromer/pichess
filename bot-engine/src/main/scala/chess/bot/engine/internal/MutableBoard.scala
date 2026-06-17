package chess.bot.engine.internal

import chess.model.board.{Bitboard, BoardLike, Position}
import chess.model.piece.{Color, Piece, PieceType}

/** Search-internal MUTABLE board for copy-make.
  *
  * Twelve raw-`Long` piece bitboards + the three cached aggregates, mutated in
  * place. Implements [[BoardLike]] (the read seam the hot NNUE / HCE / move-gen
  * / Zobrist / check readers consume) so it can stand in for an immutable
  * [[chess.model.board.BoardState]] WITHOUT re-materialising one per node —
  * that per-node `BoardState` was ~47 % of depth-6 hybrid allocation.
  * `Bitboard` is an `opaque type … = Long`, so the accessors are zero-cost
  * wrappers the JIT erases.
  *
  * Bit layout is LERF, identical to `BoardState`: bit `i` = square `i`. Lives
  * in `internal` (not `domain`) on purpose — it stays off the domain 100
  * %-coverage mandate and is exercised by `PerftSpec` + the search specs.
  *
  * Single-threaded by construction: each search thread owns its own per-ply
  * `SearchPos`/`MutableBoard` buffers via `SearchBufs`.
  */
private[engine] final class MutableBoard extends BoardLike:

  // Raw piece bitboards. Named distinctly from the BoardLike accessors
  // (a `var x` would clash with `def x`). Order index used by
  // [[movePiece]]: white P,N,B,R,Q,K = 0..5, black = 6..11.
  private var pwRaw = 0L
  private var nwRaw = 0L
  private var bwRaw = 0L
  private var rwRaw = 0L
  private var qwRaw = 0L
  private var kwRaw = 0L
  private var pbRaw = 0L
  private var nbRaw = 0L
  private var bbRaw = 0L
  private var rbRaw = 0L
  private var qbRaw = 0L
  private var kbRaw = 0L
  private var whiteRaw = 0L
  private var blackRaw = 0L
  private var occRaw = 0L

  // ── BoardLike (zero-cost opaque-Long wrappers) ──────────────────────
  def pawnsW: Bitboard = Bitboard.fromLong(pwRaw)
  def knightsW: Bitboard = Bitboard.fromLong(nwRaw)
  def bishopsW: Bitboard = Bitboard.fromLong(bwRaw)
  def rooksW: Bitboard = Bitboard.fromLong(rwRaw)
  def queensW: Bitboard = Bitboard.fromLong(qwRaw)
  def kingW: Bitboard = Bitboard.fromLong(kwRaw)
  def pawnsB: Bitboard = Bitboard.fromLong(pbRaw)
  def knightsB: Bitboard = Bitboard.fromLong(nbRaw)
  def bishopsB: Bitboard = Bitboard.fromLong(bbRaw)
  def rooksB: Bitboard = Bitboard.fromLong(rbRaw)
  def queensB: Bitboard = Bitboard.fromLong(qbRaw)
  def kingB: Bitboard = Bitboard.fromLong(kbRaw)
  def whitePieces: Bitboard = Bitboard.fromLong(whiteRaw)
  def blackPieces: Bitboard = Bitboard.fromLong(blackRaw)
  def occupancy: Bitboard = Bitboard.fromLong(occRaw)

  def contains(pos: Position): Boolean =
    (occRaw & (1L << pos.squareIdx)) != 0L

  /** The piece at `pos`, or `None`. Mirrors `BoardState.get` exactly — returns
    * one of the 13 cached `Option[Piece]` flyweights, so it's allocation-free
    * on the move-ordering hot path.
    */
  def get(pos: Position): Option[Piece] =
    val mask = 1L << pos.squareIdx
    if (pwRaw & mask) != 0L then Piece.someOf(Color.White, PieceType.Pawn)
    else if (nwRaw & mask) != 0L then
      Piece.someOf(Color.White, PieceType.Knight)
    else if (bwRaw & mask) != 0L then
      Piece.someOf(Color.White, PieceType.Bishop)
    else if (rwRaw & mask) != 0L then Piece.someOf(Color.White, PieceType.Rook)
    else if (qwRaw & mask) != 0L then Piece.someOf(Color.White, PieceType.Queen)
    else if (kwRaw & mask) != 0L then Piece.someOf(Color.White, PieceType.King)
    else if (pbRaw & mask) != 0L then Piece.someOf(Color.Black, PieceType.Pawn)
    else if (nbRaw & mask) != 0L then
      Piece.someOf(Color.Black, PieceType.Knight)
    else if (bbRaw & mask) != 0L then
      Piece.someOf(Color.Black, PieceType.Bishop)
    else if (rbRaw & mask) != 0L then Piece.someOf(Color.Black, PieceType.Rook)
    else if (qbRaw & mask) != 0L then Piece.someOf(Color.Black, PieceType.Queen)
    else if (kbRaw & mask) != 0L then Piece.someOf(Color.Black, PieceType.King)
    else None

  // ── In-place mutation (copy-make) ───────────────────────────────────

  /** Overwrite all bitboards (incl. aggregates) from another board. The `from`
    * is the parent position; `o` is read through [[BoardLike]] so the source
    * can be either a [[chess.model.board.BoardState]] (root load) or another
    * [[MutableBoard]] (parent→child).
    */
  def copyFrom(o: BoardLike): Unit =
    pwRaw = o.pawnsW.raw; nwRaw = o.knightsW.raw; bwRaw = o.bishopsW.raw
    rwRaw = o.rooksW.raw; qwRaw = o.queensW.raw; kwRaw = o.kingW.raw
    pbRaw = o.pawnsB.raw; nbRaw = o.knightsB.raw; bbRaw = o.bishopsB.raw
    rbRaw = o.rooksB.raw; qbRaw = o.queensB.raw; kbRaw = o.kingB.raw
    whiteRaw = o.whitePieces.raw
    blackRaw = o.blackPieces.raw
    occRaw = o.occupancy.raw

  /** Clear `fromIdx` and `toIdx` across all twelve bitboards (removing the
    * moving piece and any piece captured on `toIdx`), then set the piece
    * identified by `pieceIdx` (0..11, white P,N,B,R,Q,K then black) on `toIdx`.
    * Same one-shot bit logic as `BoardState.movePiece`, mutating in place.
    * `pieceIdx` is the POST-promotion piece for a promoting pawn. Aggregates
    * are NOT refreshed here — the caller batches a single
    * [[recomputeAggregates]] after all sub-moves (castling rook hop, en-passant
    * clear) are applied.
    */
  def movePiece(fromIdx: Int, toIdx: Int, pieceIdx: Int): Unit =
    val clear = ~((1L << fromIdx) | (1L << toIdx))
    pwRaw &= clear; nwRaw &= clear; bwRaw &= clear; rwRaw &= clear
    qwRaw &= clear; kwRaw &= clear; pbRaw &= clear; nbRaw &= clear
    bbRaw &= clear; rbRaw &= clear; qbRaw &= clear; kbRaw &= clear
    val set = 1L << toIdx
    pieceIdx match
      case 0  => pwRaw |= set
      case 1  => nwRaw |= set
      case 2  => bwRaw |= set
      case 3  => rwRaw |= set
      case 4  => qwRaw |= set
      case 5  => kwRaw |= set
      case 6  => pbRaw |= set
      case 7  => nbRaw |= set
      case 8  => bbRaw |= set
      case 9  => rbRaw |= set
      case 10 => qbRaw |= set
      case _  => kbRaw |= set

  /** Clear `idx` across all twelve bitboards. Used to remove the pawn captured
    * en passant (it sits on a different square than `to`).
    */
  def clearSquare(idx: Int): Unit =
    val m = ~(1L << idx)
    pwRaw &= m; nwRaw &= m; bwRaw &= m; rwRaw &= m
    qwRaw &= m; kwRaw &= m; pbRaw &= m; nbRaw &= m
    bbRaw &= m; rbRaw &= m; qbRaw &= m; kbRaw &= m

  /** Recompute the colour / occupancy aggregates from the twelve piece
    * bitboards. Called once per copy-make after the piece moves land.
    */
  def recomputeAggregates(): Unit =
    whiteRaw = pwRaw | nwRaw | bwRaw | rwRaw | qwRaw | kwRaw
    blackRaw = pbRaw | nbRaw | bbRaw | rbRaw | qbRaw | kbRaw
    occRaw = whiteRaw | blackRaw

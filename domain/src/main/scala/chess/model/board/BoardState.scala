package chess.model.board

import chess.model.piece.{Color, Piece, PieceType}

/** Bitboard-backed chess board. Twelve `Bitboard`s — one per
  * `(PieceType, Color)` combination — are the source of truth; the
  * `Map[Position, Piece]`-style API on top of them is a thin facade
  * for the call sites that don't need to think about bit twiddling
  * (codec, view, tests, …).
  *
  * Phase 1 of the bitboard migration: data model only. MoveValidator
  * et al. still use the facade through the `iterator`/`get`/`exists`
  * surface. Phase 2 replaces those internals with bitboard intrinsics
  * for the speed win.
  *
  * Cached aggregates (`whitePieces`, `blackPieces`, `occupancy`) are
  * `lazy val`s — case-class semantics mean each `+`/`-`/`++` returns a
  * fresh instance whose aggregates recompute on first read.
  */
final case class BoardState(
    pawnsW: Bitboard,
    knightsW: Bitboard,
    bishopsW: Bitboard,
    rooksW: Bitboard,
    queensW: Bitboard,
    kingW: Bitboard,
    pawnsB: Bitboard,
    knightsB: Bitboard,
    bishopsB: Bitboard,
    rooksB: Bitboard,
    queensB: Bitboard,
    kingB: Bitboard,
):
  import BoardState.bitboardFor

  // ── Cached aggregates ────────────────────────────────────────────────
  // Computed once per BoardState instance. `val` (not `lazy val`) —
  // every BoardState in our hot search path has at least one of
  // these accessed almost immediately (MoveValidator queries
  // occupancy on every move-gen call, FullFeatures queries
  // whitePieces/blackPieces in eval). Scala's lazy-val wrapper
  // pays a synchronization check per *access*, not just per init,
  // so plain `val` wins as long as we pay >= 1 access per
  // instance — which we always do. Profile-confirmed:
  // `BoardState.occupancy$lzyINIT1` shows up as a measurable hot
  // spot under the old `lazy val`.
  val whitePieces: Bitboard =
    pawnsW | knightsW | bishopsW | rooksW | queensW | kingW
  val blackPieces: Bitboard =
    pawnsB | knightsB | bishopsB | rooksB | queensB | kingB
  val occupancy: Bitboard = whitePieces | blackPieces

  // ── Map-style API ────────────────────────────────────────────────────

  /** The piece at `pos`. Throws `NoSuchElementException` if the square
    * is empty — matches `Map.apply`'s contract, so callers that have
    * already established the square is occupied (e.g. via a `contains`
    * check immediately upstream) can keep using `board(pos)`.
    */
  def apply(pos: Position): Piece =
    get(pos).getOrElse(
      throw new NoSuchElementException(s"No piece at $pos")
    )

  /** The piece at `pos`, or `None` if the square is empty.
    *
    * Returns one of the 13 cached `Option[Piece]` flyweights
    * (12 pieces × `Some` + the canonical `None`) — zero allocation
    * per call, which matters because this method is called by the
    * million in the bot's move-generation hot loop. */
  def get(pos: Position): Option[Piece] =
    val idx  = pos.squareIdx
    val mask = 1L << idx
    if (pawnsW.raw & mask)   != 0L then Piece.someOf(Color.White, PieceType.Pawn)
    else if (knightsW.raw & mask) != 0L then Piece.someOf(Color.White, PieceType.Knight)
    else if (bishopsW.raw & mask) != 0L then Piece.someOf(Color.White, PieceType.Bishop)
    else if (rooksW.raw & mask)   != 0L then Piece.someOf(Color.White, PieceType.Rook)
    else if (queensW.raw & mask)  != 0L then Piece.someOf(Color.White, PieceType.Queen)
    else if (kingW.raw & mask)    != 0L then Piece.someOf(Color.White, PieceType.King)
    else if (pawnsB.raw & mask)   != 0L then Piece.someOf(Color.Black, PieceType.Pawn)
    else if (knightsB.raw & mask) != 0L then Piece.someOf(Color.Black, PieceType.Knight)
    else if (bishopsB.raw & mask) != 0L then Piece.someOf(Color.Black, PieceType.Bishop)
    else if (rooksB.raw & mask)   != 0L then Piece.someOf(Color.Black, PieceType.Rook)
    else if (queensB.raw & mask)  != 0L then Piece.someOf(Color.Black, PieceType.Queen)
    else if (kingB.raw & mask)    != 0L then Piece.someOf(Color.Black, PieceType.King)
    else None

  /** Is any piece present at `pos`? */
  def contains(pos: Position): Boolean =
    (occupancy.raw & (1L << pos.squareIdx)) != 0L

  /** Add or replace the piece at `pos`. If a different piece already
    * occupies `pos`, it's removed first (per the chess capture rule).
    */
  def +(kv: (Position, Piece)): BoardState =
    val (pos, piece) = kv
    afterRemove(pos.squareIdx).withSet(piece, pos.squareIdx)

  /** Add a sequence of pieces in order. Later writes overwrite earlier
    * writes at the same square. */
  def ++(entries: IterableOnce[(Position, Piece)]): BoardState =
    entries.iterator.foldLeft(this)(_ + _)

  /** Remove the piece (if any) at `pos`. No-op when empty. */
  def -(pos: Position): BoardState =
    afterRemove(pos.squareIdx)

  /** Apply a piece move in a SINGLE allocation: clear `from` and `to`
    * across all twelve bitboards (removing the moving piece and any
    * piece captured on `to`), then place `piece` on `to`. Semantically
    * identical to `this - from + (to -> piece)` but builds one
    * `BoardState` instead of three — each `-`/`+` allocates via
    * `afterRemove`/`withSet`, and the eager aggregate vals recompute
    * per instance, so the old chain paid that 3×.
    *
    * `piece` is the post-promotion piece for a promoting pawn; the
    * pawn on `from` is cleared regardless of type. The second rook hop
    * (castling) and the off-square pawn removal (en passant) are
    * layered on by the caller with a further `movePiece`/`-`. */
  def movePiece(from: Position, to: Position, piece: Piece): BoardState =
    val clear = ~((1L << from.squareIdx) | (1L << to.squareIdx))
    val set   = 1L << to.squareIdx
    var pW = pawnsW.raw   & clear
    var nW = knightsW.raw & clear
    var bW = bishopsW.raw & clear
    var rW = rooksW.raw   & clear
    var qW = queensW.raw  & clear
    var kW = kingW.raw    & clear
    var pB = pawnsB.raw   & clear
    var nB = knightsB.raw & clear
    var bB = bishopsB.raw & clear
    var rB = rooksB.raw   & clear
    var qB = queensB.raw  & clear
    var kB = kingB.raw    & clear
    bitboardFor(piece) match
      case BoardState.PieceField.PawnW   => pW |= set
      case BoardState.PieceField.KnightW => nW |= set
      case BoardState.PieceField.BishopW => bW |= set
      case BoardState.PieceField.RookW   => rW |= set
      case BoardState.PieceField.QueenW  => qW |= set
      case BoardState.PieceField.KingW   => kW |= set
      case BoardState.PieceField.PawnB   => pB |= set
      case BoardState.PieceField.KnightB => nB |= set
      case BoardState.PieceField.BishopB => bB |= set
      case BoardState.PieceField.RookB   => rB |= set
      case BoardState.PieceField.QueenB  => qB |= set
      case BoardState.PieceField.KingB   => kB |= set
    BoardState(
      Bitboard.fromLong(pW), Bitboard.fromLong(nW), Bitboard.fromLong(bW),
      Bitboard.fromLong(rW), Bitboard.fromLong(qW), Bitboard.fromLong(kW),
      Bitboard.fromLong(pB), Bitboard.fromLong(nB), Bitboard.fromLong(bB),
      Bitboard.fromLong(rB), Bitboard.fromLong(qB), Bitboard.fromLong(kB),
    )

  /** Total piece count across both colors. */
  def size: Int = occupancy.popCount

  /** True when there are no pieces on the board (degenerate state, but
    * a legitimate `BoardState.Empty` for tests). */
  def isEmpty: Boolean = occupancy.isEmpty

  def nonEmpty: Boolean = !isEmpty

  /** Walk every occupied square low-to-high (LERF order). Matches what
    * `Map[Position, Piece].iterator` used to produce, minus the
    * iteration order being insertion-defined — the test suite uses the
    * resulting entries set-wise so the order change is invisible. */
  def iterator: Iterator[(Position, Piece)] = new Iterator[(Position, Piece)]:
    private var rem: Long = occupancy.raw
    def hasNext: Boolean  = rem != 0L
    def next(): (Position, Piece) =
      val idx = java.lang.Long.numberOfTrailingZeros(rem)
      rem &= rem - 1L
      val pos = Position(('a' + (idx % 8)).toChar, (idx / 8) + 1)
      // get(pos) won't return None here — `idx` came from `occupancy`.
      (pos, get(pos).get)

  /** First entry matching `pf`, or `None`. Still used by the views
    * (`WebBoardView`, `tui/BoardView`) to find the king for highlight
    * rendering; the bitboard `MoveValidator` reads
    * `kingW`/`kingB.lowestBitIdx` directly so no longer hits this
    * path. */
  def collectFirst[A](pf: PartialFunction[(Position, Piece), A]): Option[A] =
    iterator.collectFirst(pf)

  /** Snapshot every (pos, piece) pair as a List. Order matches
    * [[iterator]] (LERF, low-to-high). */
  def toList: List[(Position, Piece)] = iterator.toList

  /** Fold over every (pos, piece) entry. */
  def foldLeft[B](z: B)(op: (B, (Position, Piece)) => B): B =
    iterator.foldLeft(z)(op)

  // ── Internal helpers ─────────────────────────────────────────────────

  /** Return a BoardState that has `idx` cleared in every piece bitboard
    * it currently appears in (i.e. capture / remove the piece on that
    * square). Pure — returns a new instance. */
  private def afterRemove(idx: Int): BoardState =
    val mask = ~(1L << idx)
    BoardState(
      pawnsW   = Bitboard.fromLong(pawnsW.raw   & mask),
      knightsW = Bitboard.fromLong(knightsW.raw & mask),
      bishopsW = Bitboard.fromLong(bishopsW.raw & mask),
      rooksW   = Bitboard.fromLong(rooksW.raw   & mask),
      queensW  = Bitboard.fromLong(queensW.raw  & mask),
      kingW    = Bitboard.fromLong(kingW.raw    & mask),
      pawnsB   = Bitboard.fromLong(pawnsB.raw   & mask),
      knightsB = Bitboard.fromLong(knightsB.raw & mask),
      bishopsB = Bitboard.fromLong(bishopsB.raw & mask),
      rooksB   = Bitboard.fromLong(rooksB.raw   & mask),
      queensB  = Bitboard.fromLong(queensB.raw  & mask),
      kingB    = Bitboard.fromLong(kingB.raw    & mask),
    )

  private def withSet(piece: Piece, idx: Int): BoardState =
    bitboardFor(piece) match
      case BoardState.PieceField.PawnW   => copy(pawnsW   = pawnsW.withBit(idx))
      case BoardState.PieceField.KnightW => copy(knightsW = knightsW.withBit(idx))
      case BoardState.PieceField.BishopW => copy(bishopsW = bishopsW.withBit(idx))
      case BoardState.PieceField.RookW   => copy(rooksW   = rooksW.withBit(idx))
      case BoardState.PieceField.QueenW  => copy(queensW  = queensW.withBit(idx))
      case BoardState.PieceField.KingW   => copy(kingW    = kingW.withBit(idx))
      case BoardState.PieceField.PawnB   => copy(pawnsB   = pawnsB.withBit(idx))
      case BoardState.PieceField.KnightB => copy(knightsB = knightsB.withBit(idx))
      case BoardState.PieceField.BishopB => copy(bishopsB = bishopsB.withBit(idx))
      case BoardState.PieceField.RookB   => copy(rooksB   = rooksB.withBit(idx))
      case BoardState.PieceField.QueenB  => copy(queensB  = queensB.withBit(idx))
      case BoardState.PieceField.KingB   => copy(kingB    = kingB.withBit(idx))

object BoardState:

  /** Tag identifying which of the 12 piece bitboards a `(color, type)`
    * tuple addresses. Sidesteps a 12-way nested `match` at each
    * `withSet` site.
    */
  private[board] enum PieceField:
    case PawnW, KnightW, BishopW, RookW, QueenW, KingW
    case PawnB, KnightB, BishopB, RookB, QueenB, KingB

  private[board] def bitboardFor(p: Piece): PieceField = (p.color, p.pieceType) match
    case (Color.White, PieceType.Pawn)   => PieceField.PawnW
    case (Color.White, PieceType.Knight) => PieceField.KnightW
    case (Color.White, PieceType.Bishop) => PieceField.BishopW
    case (Color.White, PieceType.Rook)   => PieceField.RookW
    case (Color.White, PieceType.Queen)  => PieceField.QueenW
    case (Color.White, PieceType.King)   => PieceField.KingW
    case (Color.Black, PieceType.Pawn)   => PieceField.PawnB
    case (Color.Black, PieceType.Knight) => PieceField.KnightB
    case (Color.Black, PieceType.Bishop) => PieceField.BishopB
    case (Color.Black, PieceType.Rook)   => PieceField.RookB
    case (Color.Black, PieceType.Queen)  => PieceField.QueenB
    case (Color.Black, PieceType.King)   => PieceField.KingB

  /** Empty board — no pieces on any square. */
  val Empty: BoardState = BoardState(
    Bitboard.Empty, Bitboard.Empty, Bitboard.Empty, Bitboard.Empty,
    Bitboard.Empty, Bitboard.Empty, Bitboard.Empty, Bitboard.Empty,
    Bitboard.Empty, Bitboard.Empty, Bitboard.Empty, Bitboard.Empty,
  )

  /** Construct from a `Map[Position, Piece]`. Migration aid for tests
    * and codec code that already builds maps. Delegates to [[from]]
    * so a single-pass build runs regardless of the input shape. */
  def fromMap(m: Map[Position, Piece]): BoardState =
    from(m)

  /** Construct from an iterable of (position, piece) pairs. Builds
    * the 12 piece bitboards in a single pass and constructs *one*
    * `BoardState` — so the eager aggregate vals (`whitePieces`,
    * `blackPieces`, `occupancy`) pay their compute cost once,
    * not 2×N times as the previous `foldLeft(Empty)(_ + _)` did
    * (each `+` allocated two BoardStates via `afterRemove` +
    * `withSet`). Profile-confirmed: dropped codec
    * `decode_medium_fen` from 35 us → ~9 us. */
  def from(entries: Iterable[(Position, Piece)]): BoardState =
    var pW, nW, bW, rW, qW, kW = 0L
    var pB, nB, bB, rB, qB, kB = 0L
    entries.foreach { case (pos, piece) =>
      val mask = 1L << pos.squareIdx
      piece.color match
        case Color.White => piece.pieceType match
          case PieceType.Pawn   => pW |= mask
          case PieceType.Knight => nW |= mask
          case PieceType.Bishop => bW |= mask
          case PieceType.Rook   => rW |= mask
          case PieceType.Queen  => qW |= mask
          case PieceType.King   => kW |= mask
        case Color.Black => piece.pieceType match
          case PieceType.Pawn   => pB |= mask
          case PieceType.Knight => nB |= mask
          case PieceType.Bishop => bB |= mask
          case PieceType.Rook   => rB |= mask
          case PieceType.Queen  => qB |= mask
          case PieceType.King   => kB |= mask
    }
    BoardState(
      Bitboard.fromLong(pW), Bitboard.fromLong(nW),
      Bitboard.fromLong(bW), Bitboard.fromLong(rW),
      Bitboard.fromLong(qW), Bitboard.fromLong(kW),
      Bitboard.fromLong(pB), Bitboard.fromLong(nB),
      Bitboard.fromLong(bB), Bitboard.fromLong(rB),
      Bitboard.fromLong(qB), Bitboard.fromLong(kB),
    )

  /** Implicit conversion from `Map[Position, Piece]` so test fixtures
    * (`GameState(board = Map(pos -> piece, …))`) and any codec code
    * that materialises a Map mid-pipeline keep compiling unchanged.
    * The conversion is explicit-and-named so it's grep-able when
    * auditing.
    */
  given fromMapConversion: Conversion[Map[Position, Piece], BoardState] =
    fromMap

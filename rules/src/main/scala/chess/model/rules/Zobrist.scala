package chess.model.rules

import scala.util.Random

import chess.model.board.{Position, PositionView}
import chess.model.piece.{Color, Piece}

/** Zobrist hashing for chess positions.
  *
  * Represents a position as a single 64-bit integer by XOR-folding precomputed
  * random values for each piece×square occupancy, castling right, en passant
  * file, and active color.
  *
  * All tables are precomputed once from a fixed seed, so the hash of a given
  * [[GameState]] is stable across runs and JVM instances — a requirement for
  * persistent transposition tables and opening books later.
  *
  * The current implementation computes the hash from scratch for each state
  * (O(board size)). A later optimisation can compute hashes incrementally via
  * XOR deltas during move application (O(1) per move), but requires careful
  * parity with the from-scratch computation to avoid silent divergence.
  *
  * @see
  *   [[chess.codec.FenSerializer.positionKey]] — the string-based identity
  *   function Zobrist is contracted to agree with. Equivalence is enforced by
  *   the test suite.
  */
object Zobrist:

  /** Fixed seed. Changing this invalidates any persisted hash (none currently
    * exist, but this constant is part of the wire format once transposition
    * tables or opening books are introduced).
    */
  private val seed: Long = 0x1234567890abcdefL

  // ── Precomputed tables ────────────────────────────────────────────────────
  //
  // Drained from a single Random in declaration order so the seed uniquely
  // determines every entry. `scala.util.Random` wraps `java.util.Random`, which
  // is JVM-stable — the same seed yields the same sequence everywhere.

  private val rng: Random = new Random(seed)

  /** pieces(pieceIndex)(squareIndex). 12 pieces × 64 squares. */
  private val pieces: Array[Array[Long]] =
    Array.fill(12)(Array.fill(64)(rng.nextLong()))

  /** Per-flag castling randoms: 0=wK, 1=wQ, 2=bK, 3=bQ. */
  private val castling: Array[Long] =
    Array.fill(4)(rng.nextLong())

  /** Per-file en passant randoms (file a..h → index 0..7). */
  private val enPassantFile: Array[Long] =
    Array.fill(8)(rng.nextLong())

  /** Toggled when Black is to move. */
  private val blackToMove: Long = rng.nextLong()

  // Sensitivity (no table entry is zero) is enforced by
  // [[chess.codec.PositionIdentityBehaviors]] running against Zobrist.hash:
  // a zero entry would collapse that feature's contribution to the hash and
  // fail the matching "… alone changes the key" test.

  // `pieceIndex` / `squareIndex` encode the table-index scheme that
  // `hash` applies inline (it walks the 12 piece bitboards by hardcoded
  // index for speed). Retained as the single readable definition of that
  // scheme; `private[rules]` so the contract test can pin the formula.
  private[rules] def pieceIndex(piece: Piece): Int =
    piece.color.ordinal * 6 + piece.pieceType.ordinal

  private[rules] def squareIndex(pos: Position): Int =
    (pos.col - 'a') + (pos.row - 1) * 8

  /** Hash a [[GameState]] to a 64-bit Zobrist key.
    *
    * The hash is sensitive to exactly the four FIDE position-identity fields:
    *   1. piece placement 2. active color 3. castling rights 4. en passant
    *      target (file-only; the rank is redundant in chess)
    *
    * It is '''not''' sensitive to `halfmoveClock`, `fullmoveNumber`, `status`,
    * or `inCheck` — matching the contract of
    * [[chess.codec.FenSerializer.positionKey]].
    */
  def hash(state: PositionView): Long =
    // Walk each of the 12 piece bitboards via primitive bit-iteration
    // instead of `state.board.foldLeft { case (acc, (pos, piece)) => … }`.
    // The foldLeft version constructed a Tuple3 + boxed Long
    // accumulator at every set square — profile showed ~12 boxToLong
    // samples here. Bit-iteration uses `numberOfTrailingZeros` + bit
    // clear, fully primitive, no allocations.
    val bs = state.board
    // Index mapping follows `pieceIndex = color.ordinal*6 + pieceType.ordinal`
    // with PieceType enum order King, Queen, Rook, Bishop, Knight, Pawn (see
    // PieceType.scala). So White starts at idx 0 (king) and Black starts at 6.
    val boardHash =
      hashBits(bs.kingW.raw,    pieces(0)) ^
        hashBits(bs.queensW.raw,  pieces(1)) ^
        hashBits(bs.rooksW.raw,   pieces(2)) ^
        hashBits(bs.bishopsW.raw, pieces(3)) ^
        hashBits(bs.knightsW.raw, pieces(4)) ^
        hashBits(bs.pawnsW.raw,   pieces(5)) ^
        hashBits(bs.kingB.raw,    pieces(6)) ^
        hashBits(bs.queensB.raw,  pieces(7)) ^
        hashBits(bs.rooksB.raw,   pieces(8)) ^
        hashBits(bs.bishopsB.raw, pieces(9)) ^
        hashBits(bs.knightsB.raw, pieces(10)) ^
        hashBits(bs.pawnsB.raw,   pieces(11))
    val cr = state.castlingRights
    val castlingHash =
      (if cr.whiteKingSide then castling(0) else 0L) ^
        (if cr.whiteQueenSide then castling(1) else 0L) ^
        (if cr.blackKingSide then castling(2) else 0L) ^
        (if cr.blackQueenSide then castling(3) else 0L)
    val epHash =
      state.enPassantTarget.fold(0L)(pos => enPassantFile(pos.col - 'a'))
    val colorHash =
      if state.activeColor == Color.Black then blackToMove else 0L
    boardHash ^ castlingHash ^ epHash ^ colorHash

  /** Pawn-structure-only Zobrist key. XOR of the pawn random
    * draws — sensitive only to where pawns sit (color-aware via
    * the two separate sub-tables), not to anything else. Used as
    * the index key for the correction-history table: pawn skeletons
    * change slowly, so the same key recurs across many positions in
    * one search subtree, giving the corrhist signal enough
    * accumulations to converge.
    */
  def pawnHash(state: PositionView): Long =
    val bs = state.board
    hashBits(bs.pawnsW.raw, pieces(5)) ^ hashBits(bs.pawnsB.raw, pieces(11))

  /** Material-signature key: piece counts per (color, type), packed
    * so distinct compositions hash distinctly. The exact mixing isn't
    * important — what matters is that the same material balance
    * always returns the same Long, and different balances are very
    * unlikely to collide. Used as the second correction-history
    * index alongside [[pawnHash]]. */
  def materialKey(state: PositionView): Long =
    val bs = state.board
    // 8 piece counts (skip kings — always 1 each) × 5 bits each = 40 bits.
    // Bishops sometimes exceed 4 (under-promotion), allow 5 bits to be safe.
    val pW = bs.pawnsW.popCount.toLong   & 0x1f
    val nW = bs.knightsW.popCount.toLong & 0x1f
    val bW = bs.bishopsW.popCount.toLong & 0x1f
    val rW = bs.rooksW.popCount.toLong   & 0x1f
    val qW = bs.queensW.popCount.toLong  & 0x1f
    val pB = bs.pawnsB.popCount.toLong   & 0x1f
    val nB = bs.knightsB.popCount.toLong & 0x1f
    val bB = bs.bishopsB.popCount.toLong & 0x1f
    val rB = bs.rooksB.popCount.toLong   & 0x1f
    val qB = bs.queensB.popCount.toLong  & 0x1f
    val packed =
      pW | (nW << 5) | (bW << 10) | (rW << 15) | (qW << 20) |
        (pB << 25) | (nB << 30) | (bB << 35) | (rB << 40) | (qB << 45)
    // Splitmix-style avalanche so neighbouring material balances
    // map to distant slots rather than clustering.
    var x = packed
    x ^= x >>> 30
    x *= 0xBF58476D1CE4E5B9L
    x ^= x >>> 27
    x *= 0x94D049BB133111EBL
    x ^= x >>> 31
    x

  private inline def hashBits(bits: Long, table: Array[Long]): Long =
    var b = bits
    var h = 0L
    while b != 0L do
      val sq = java.lang.Long.numberOfTrailingZeros(b)
      h ^= table(sq)
      b &= b - 1L
    h

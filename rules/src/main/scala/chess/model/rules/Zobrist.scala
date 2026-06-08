package chess.model.rules

import scala.util.Random

import chess.model.board.{GameState, Position}
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

  private def pieceIndex(piece: Piece): Int =
    piece.color.ordinal * 6 + piece.pieceType.ordinal

  private def squareIndex(pos: Position): Int =
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
  def hash(state: GameState): Long =
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

  private inline def hashBits(bits: Long, table: Array[Long]): Long =
    var b = bits
    var h = 0L
    while b != 0L do
      val sq = java.lang.Long.numberOfTrailingZeros(b)
      h ^= table(sq)
      b &= b - 1L
    h

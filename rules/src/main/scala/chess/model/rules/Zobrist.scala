package chess.model.rules

import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}

import scala.util.Random

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
    *   1. piece placement
    *   2. active color
    *   3. castling rights
    *   4. en passant target (file-only; the rank is redundant in chess)
    *
    * It is '''not''' sensitive to `halfmoveClock`, `fullmoveNumber`, `status`,
    * or `inCheck` — matching the contract of
    * [[chess.codec.FenSerializer.positionKey]].
    */
  def hash(state: GameState): Long =
    val boardHash = state.board.foldLeft(0L) { case (acc, (pos, piece)) =>
      acc ^ pieces(pieceIndex(piece))(squareIndex(pos))
    }
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

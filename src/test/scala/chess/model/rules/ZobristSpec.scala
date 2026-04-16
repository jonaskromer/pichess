package chess.model.rules

import chess.codec.{FenSerializer, PositionIdentityBehaviors}
import chess.controller.MoveParser
import chess.model.GameError
import chess.model.board.GameState
import zio.*
import zio.test.*

/** Contract tests for [[Zobrist.hash]] as a position-identity function.
  *
  * The shared behaviors (sensitivity, symmetry) are defined in
  * [[PositionIdentityBehaviors]] and also instantiated by
  * [[chess.codec.PositionKeySensitivitySpec]] against FEN-based positionKey.
  *
  * Zobrist-specific tests added here:
  *   - '''Distinctness sweep''': replay the curated game sequences used
  *     elsewhere in the suite, collect every state reached, and assert that
  *     the Zobrist hash partitions them identically to the FEN position-key.
  *     Any divergence is a Zobrist correctness bug.
  *   - '''Stability''': the hash of the initial position is non-zero and
  *     consistent across calls — a minimal regression guard against a change
  *     to the seed or the tables accidentally collapsing to zero.
  */
object ZobristSpec extends ZIOSpecDefault:

  /** Replay curated sequences and collect all reached states. Every distinct
    * position across these sequences contributes to the distinctness sweep.
    */
  private def walkSequence(
      moves: List[String]
  ): IO[GameError, List[GameState]] =
    ZIO
      .foldLeft(moves)((GameState.initial, List(GameState.initial))) {
        case ((state, acc), san) =>
          for
            move <- MoveParser.parse(san, state)
            next <- Game.applyMove(state, move)
          yield (next, next :: acc)
      }
      .map(_._2.reverse)

  private val corpusSequences: List[List[String]] = List(
    // Ruy Lopez with both castling kingside
    List("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7",
      "Re1", "b5", "Bb3", "d6", "c3", "O-O"),
    // Najdorf with opposite-side castling
    List("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6",
      "Be3", "e5", "Nb3", "Be6", "f3", "b5", "Qd2", "Nbd7", "O-O-O", "Be7",
      "g4", "O-O"),
    // En passant capture cycle
    List("e4", "Nf6", "e5", "d5", "exd6"),
    // Scholar's mate
    List("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#"),
    // Knight shuffle (generates repeated positions)
    List("Nf3", "Nc6", "Ng1", "Nb8", "Nf3", "Nc6", "Ng1", "Nb8")
  )

  def spec = suite("Zobrist.hash")(
    PositionIdentityBehaviors.behaviors(Zobrist.hash),
    test("hash of initial is non-zero and stable") {
      val h = Zobrist.hash(GameState.initial)
      assertTrue(h != 0L, h == Zobrist.hash(GameState.initial))
    },
    test(
      "distinctness sweep: Zobrist partitions corpus identically to FEN positionKey"
    ) {
      // For every pair of positions reachable in the corpus, assert that
      // their Zobrist hashes agree iff their FEN position-keys agree. This
      // is the definition of "equivalent as identity functions" — and the
      // strongest single test for Zobrist correctness, since it locks the
      // function to the known-good reference across every pair.
      for states <- ZIO.foreach(corpusSequences)(walkSequence).map(_.flatten)
      yield
        val pairs = for
          i <- states.indices
          j <- states.indices
          if i < j
        yield (states(i), states(j))
        val disagreements = pairs.filter { case (a, b) =>
          val fenEq = FenSerializer.positionKey(a) ==
            FenSerializer.positionKey(b)
          val zobEq = Zobrist.hash(a) == Zobrist.hash(b)
          fenEq != zobEq
        }
        assertTrue(disagreements.isEmpty)
    }
  )

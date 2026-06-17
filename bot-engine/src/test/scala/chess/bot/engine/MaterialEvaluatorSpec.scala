package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

/** Material evaluator behaviour pinned via well-known FEN positions.
  *
  * The eval is white-POV centipawn-valued, so the assertions read as "white
  * minus black material in centipawns". Test positions are picked so the math
  * is by-inspection from the FEN — pawn = 100, knight = 320, bishop = 330, rook
  * \= 500, queen = 900.
  */
object MaterialEvaluatorSpec extends ZIOSpecDefault:

  /** Helper to parse a FEN and feed it to the material eval. */
  private def materialOf(fen: String): ZIO[Any, chess.model.GameError, Int] =
    FenParserRegex.parse(fen).map(Evaluator.materialOnly.evaluate)

  def spec = suite("MaterialEvaluator")(
    test("returns 0 on the symmetrical starting position") {
      for score <- materialOf(
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
      yield assertTrue(score == 0)
    },
    test("returns +900 when white has an extra queen") {
      // Standard start minus the black queen on d8 → white +900.
      for score <- materialOf(
          "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
      yield assertTrue(score == 900)
    },
    test("returns -500 when black has an extra rook (white missing h1 rook)") {
      for score <- materialOf(
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN1 w Qkq - 0 1"
        )
      yield assertTrue(score == -500)
    },
    test("returns 0 on a king-vs-king endgame (kings score zero)") {
      for score <- materialOf("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
      yield assertTrue(score == 0)
    },
    test("counts pawns at 100 cp each") {
      // White has 8 pawns, black has 7 (missing a7). +100 for white.
      for score <- materialOf(
          "rnbqkbnr/1ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
      yield assertTrue(score == 100)
    },
    test("aggregates correctly across multiple piece-type imbalances") {
      // White: KQR (rook on a1 only, both bishops and knights missing).
      // Black: K + 8 pawns.
      // White material: 900 (Q) + 500 (R) = 1400.
      // Black material: 8 × 100 = 800.
      // Net: 1400 - 800 = 600.
      for score <- materialOf("4k3/pppppppp/8/8/8/8/8/R3K2Q w Q - 0 1")
      yield assertTrue(score == 600)
    }
  )

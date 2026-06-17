package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

object TunedEvaluatorSpec extends ZIOSpecDefault:

  /** Weights matching the hand-coded [[MaterialEvaluator]] — so the
    * tuned-evaluator over the material extractor agrees with it on every
    * position.
    */
  private val materialWeights: Map[String, Int] = Map(
    "pawn" -> 100,
    "knight" -> 320,
    "bishop" -> 330,
    "rook" -> 500,
    "queen" -> 900
  )

  private val tuned: Evaluator =
    TunedEvaluator(materialWeights, FeatureExtractor.material)

  def spec = suite("TunedEvaluator")(
    test("agrees with MaterialEvaluator at material-balanced positions") {
      for state <- FenParserRegex.parse(
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
      yield assertTrue(
        tuned.evaluate(state) == Evaluator.materialOnly.evaluate(state),
        tuned.evaluate(state) == 0
      )
    },
    test("matches MaterialEvaluator at material-imbalanced positions") {
      // Black missing the queen.
      for state <- FenParserRegex.parse(
          "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
      yield assertTrue(
        tuned.evaluate(state) == Evaluator.materialOnly.evaluate(state),
        tuned.evaluate(state) == 900
      )
    },
    test("scales linearly with weight changes") {
      // Halving every weight should halve the eval.
      val halfWeights = materialWeights.view.mapValues(_ / 2).toMap
      val halfTuned = TunedEvaluator(halfWeights, FeatureExtractor.material)
      for state <- FenParserRegex.parse(
          "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
      yield assertTrue(
        tuned.evaluate(state) == 900,
        halfTuned.evaluate(state) == 450
      )
    },
    test("treats missing weights as 0 (extractor key without weight)") {
      // A weight map that only sets pawn — knights, bishops, etc.,
      // count as 0 weight. So the eval reflects only pawn imbalance.
      val pawnOnly = Map("pawn" -> 100)
      val pawnEval = TunedEvaluator(pawnOnly, FeatureExtractor.material)
      // Black missing the queen + a pawn.
      for state <- FenParserRegex.parse(
          "rnb1kbnr/1ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
      yield assertTrue(pawnEval.evaluate(state) == 100)
    }
  )

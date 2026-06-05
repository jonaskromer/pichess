package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

object TaperedEvaluatorSpec extends ZIOSpecDefault:

  def spec = suite("TaperedEvaluator")(
    test("blends `_mg` + `_eg` weights by the position's game phase") {
      // White has the bishop pair, black doesn't → bishop_pair = +1.
      // Set "bishop_pair_mg" = 30, "bishop_pair_eg" = 60. At full
      // opening (phase = 1) the eval should be ~30, at full endgame
      // ~60. We're at the starting position (phase = 1) so the value
      // is purely the mg weight.
      val weights = Map(
        "bishop_pair_mg" -> 30,
        "bishop_pair_eg" -> 60,
      )
      val eval = TaperedEvaluator(weights, FeatureExtractor.full)
      for
        // Manufactured: white bishops c1+f1, black bishops removed.
        // Position has both queens + all minor pieces; phase ≈ 1.0
        // minus the missing bishops' contribution (2 phase units
        // gone, raw = 22 → phase = 22/24 ≈ 0.917).
        startish <- FenParserRegex.parse(
                      "rn1qk1nr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                    )
      yield
        val score = eval.evaluate(startish)
        // Mostly opening, so closer to 30 than 60. Allow a bit of
        // slack because the phase factor isn't exactly 1.
        assertTrue(score >= 25 && score <= 35)
    },
    test("legacy (un-suffixed) weights fall back to the same value for both phases") {
      // A snapshot like v1.json carries `pawn = 100` with no
      // `_mg`/`_eg` siblings. The evaluator must use that value for
      // both branches, so the eval at any phase collapses to
      // `weight * count`.
      val legacyWeights = Map("pawn" -> 100)
      val eval = TaperedEvaluator(legacyWeights, FeatureExtractor.full)
      for
        // White up a pawn: difference of +1 pawn anywhere on the board.
        upOnePawn <- FenParserRegex.parse(
                       "rnbqkbnr/1ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                     )
      yield
        val score = eval.evaluate(upOnePawn)
        // The exact score depends on which other features are
        // active (PST contributions etc.). Without any other tuned
        // weights configured, only `pawn = 100` fires → +100.
        assertTrue(score == 100)
    },
    test("tapered weights override legacy when both are present") {
      // If both `pawn` and `pawn_mg` are in the map, the tapered
      // lookup must prefer `pawn_mg` at the opening end.
      val mixed = Map("pawn" -> 100, "pawn_mg" -> 200, "pawn_eg" -> 80)
      val eval = TaperedEvaluator(mixed, FeatureExtractor.full)
      for start <- FenParserRegex.parse(
                     "rnbqkbnr/1ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
      yield
        // Starting-position-shape → phase very close to 1. With
        // pawn_mg = 200 → eval ≈ +200 (one extra white pawn, all weight on mg).
        val score = eval.evaluate(start)
        assertTrue(score == 200)
    },
    test("zero weights give zero eval everywhere") {
      val eval = TaperedEvaluator(Map.empty, FeatureExtractor.full)
      for state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
      yield assertTrue(eval.evaluate(state) == 0)
    },
  )

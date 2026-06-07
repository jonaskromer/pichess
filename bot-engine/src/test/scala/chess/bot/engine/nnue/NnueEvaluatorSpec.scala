package chess.bot.engine.nnue

import zio.test.*

import chess.codec.FenParserRegex

/** Sanity checks for the NNUE inference: load the baked v1 net,
  * evaluate a few hand-chosen positions, assert the magnitudes
  * land in sensible centipawn ranges. Doesn't pin specific
  * numerical values (the net is trained from a small dataset and
  * its specific outputs will drift across re-trains); pinning
  * inequalities is enough to catch a broken loader. */
object NnueEvaluatorSpec extends ZIOSpecDefault:

  def spec = suite("NnueEvaluator")(
    test("loads the baked /nnue-v1.bin resource") {
      assertTrue(NnueEvaluator.loadResource("/nnue-v1.bin").isDefined)
    },
    test("starting position evaluates close to zero (symmetric)") {
      val nnue = NnueEvaluator.loadResource("/nnue-v1.bin").get
      for state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
      // First move advantage in this net could be ±100; just rule
      // out gross misloading where eval is in the thousands.
      yield assertTrue(math.abs(nnue.evaluate(state)) < 500)
    },
    test("white up a queen evaluates positive") {
      val nnue = NnueEvaluator.loadResource("/nnue-v1.bin").get
      // White has queen + king vs black has king only
      for state <- FenParserRegex.parse("4k3/8/8/8/8/8/8/3QK3 w - - 0 1")
      yield assertTrue(nnue.evaluate(state) > 100)
    },
    test("black up a queen evaluates negative (white POV)") {
      val nnue = NnueEvaluator.loadResource("/nnue-v1.bin").get
      for state <- FenParserRegex.parse("3qk3/8/8/8/8/8/8/4K3 w - - 0 1")
      yield assertTrue(nnue.evaluate(state) < -100)
    },
  )

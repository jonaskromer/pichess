package chess.bot.engine.nnue

import zio.*
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
    // The incremental accumulator must exactly equal a from-scratch
    // rebuild, for EVERY move type, and be reversible — otherwise the
    // search would silently evaluate the wrong thing. Each pair is a
    // single real move; we apply it via `applyDiff` and compare to
    // `refreshInto` on the resulting board (both perspectives), check
    // `evaluateFrom` matches `evaluate`, then unmake and check we're
    // back where we started.
    test("incremental applyDiff == full refresh, reversible, eval-parity (all move types)") {
      val nnue = NnueEvaluator.loadResource("/nnue-v1.bin").get
      val pairs = List(
        ("quiet (e2e4)",
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
          "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"),
        ("capture (exd5)",
          "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2",
          "rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2"),
        ("castle (O-O)",
          "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1",
          "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R4RK1 b kq - 1 1"),
        ("en passant (exf6)",
          "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
          "rnbqkbnr/ppp1p1pp/5P2/3p4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3"),
        ("promotion (a8=Q)",
          "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
          "Q3k3/8/8/8/8/8/8/4K3 b - - 0 1"),
      )
      ZIO
        .foreach(pairs) { case (name, beforeFen, afterFen) =>
          for
            before <- FenParserRegex.parse(beforeFen)
            after  <- FenParserRegex.parse(afterFen)
          yield
            val acc = nnue.freshAccumulator()
            nnue.refreshInto(acc, before.board)

            val fullAfter = nnue.freshAccumulator()
            nnue.refreshInto(fullAfter, after.board)

            // Make the move incrementally → must equal a full rebuild.
            nnue.applyDiff(acc, before.board, after.board)
            val matchesAfter =
              acc.white.sameElements(fullAfter.white) &&
                acc.black.sameElements(fullAfter.black)
            val evalParity =
              nnue.evaluateFrom(acc, after.activeColor) == nnue.evaluate(after)

            // Unmake → must restore the original.
            nnue.applyDiff(acc, after.board, before.board)
            val fullBefore = nnue.freshAccumulator()
            nnue.refreshInto(fullBefore, before.board)
            val reversible =
              acc.white.sameElements(fullBefore.white) &&
                acc.black.sameElements(fullBefore.black)

            assertTrue(matchesAfter, evalParity, reversible) ?? name
        }
        .map(_.reduce(_ && _))
    },
  )

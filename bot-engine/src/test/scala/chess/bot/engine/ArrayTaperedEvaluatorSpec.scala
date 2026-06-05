package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

/** Equivalence + smoke tests for [[ArrayTaperedEvaluator]].
  *
  * The array-backed evaluator is a drop-in replacement for
  * [[TaperedEvaluator]] on the search hot path. The two must return
  * identical scores for the same weights + position; this spec pins
  * that across a representative position spread (start / mid-game /
  * Kiwipete / endgame), under both fully-tapered and legacy
  * un-suffixed weight snapshots.
  */
object ArrayTaperedEvaluatorSpec extends ZIOSpecDefault:

  private val positions: List[String] = List(
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "r1bqkb1r/1ppp1ppp/p1n2n2/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5",
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "4k3/8/8/8/8/8/8/3QK3 w - - 0 1",
  )

  // Material-only weights (un-suffixed) — exercises the legacy
  // fallback path in `lookupWeight`.
  private val legacyWeights: Map[String, Int] = Map(
    "pawn"   -> 100,
    "knight" -> 320,
    "bishop" -> 330,
    "rook"   -> 500,
    "queen"  -> 900,
  )

  // Tapered weights with both `_mg` and `_eg` variants — the path
  // production tuned snapshots will take.
  private val taperedWeights: Map[String, Int] =
    TaperedFeatureExtractor.defaultSeedWeights

  def spec = suite("ArrayTaperedEvaluator")(
    suite("equivalence with TaperedEvaluator")(
      test("legacy un-suffixed weights produce the same score on every fixture") {
        val arrEval = ArrayTaperedEvaluator(legacyWeights)
        val refEval = TaperedEvaluator(legacyWeights, FeatureExtractor.full)
        ZIO
          .foreach(positions)(FenParserRegex.parse)
          .map { states =>
            assertTrue(
              states.forall { s =>
                arrEval.evaluate(s) == refEval.evaluate(s)
              }
            )
          }
      },
      test("fully tapered weights produce the same score on every fixture") {
        val arrEval = ArrayTaperedEvaluator(taperedWeights)
        val refEval = TaperedEvaluator(taperedWeights, FeatureExtractor.full)
        ZIO
          .foreach(positions)(FenParserRegex.parse)
          .map { states =>
            assertTrue(
              states.forall { s =>
                arrEval.evaluate(s) == refEval.evaluate(s)
              }
            )
          }
      },
      test("missing weights default to 0 (same as reference)") {
        // An "intentionally sparse" snapshot — most keys absent → 0.
        // Both evaluators should agree on score = (only pawn contributes).
        val partial = Map("pawn_mg" -> 100, "pawn_eg" -> 100)
        val arrEval = ArrayTaperedEvaluator(partial)
        val refEval = TaperedEvaluator(partial, FeatureExtractor.full)
        for state <- FenParserRegex.parse(
                       "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                     )
        yield assertTrue(arrEval.evaluate(state) == refEval.evaluate(state))
      },
    ),
  )

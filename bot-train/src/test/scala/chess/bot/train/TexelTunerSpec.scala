package chess.bot.train

import zio.test.*

/** Behavioural specs for the Texel tuner.
  *
  * The convergence test is the headline check: we generate a
  * synthetic dataset where the "true" piece value is 100 cp, seed the
  * tuner with a deliberately-wrong initial guess (50), and verify
  * that after tuning the weight is close to the true value (within
  * 20%). That confirms the loss surface + coordinate descent + step
  * schedule cooperate the way the math says they should.
  */
object TexelTunerSpec extends ZIOSpecDefault:

  private val K = 0.4

  def spec = suite("TexelTuner")(
    suite("evaluate")(
      test("returns 0 for an empty feature map") {
        assertTrue(TexelTuner.evaluate(Map.empty, Map("pawn" -> 100)) == 0)
      },
      test("computes a linear combination of feature × weight") {
        // 2 pawns × 100 + 1 knight × 300 = 500
        assertTrue(
          TexelTuner.evaluate(
            features = Map("pawn" -> 2, "knight" -> 1),
            weights  = Map("pawn" -> 100, "knight" -> 300),
          ) == 500
        )
      },
      test("ignores feature keys with no weight (treats them as 0)") {
        assertTrue(
          TexelTuner.evaluate(
            features = Map("pawn" -> 2, "queen" -> 1),
            weights  = Map("pawn" -> 100),
          ) == 200
        )
      },
    ),
    suite("sigmoid")(
      test("returns 0.5 at zero input") {
        assertTrue(math.abs(TexelTuner.sigmoid(0.0) - 0.5) < 1e-9)
      },
      test("monotonically increases with input") {
        assertTrue(
          TexelTuner.sigmoid(-10) < TexelTuner.sigmoid(0),
          TexelTuner.sigmoid(0)   < TexelTuner.sigmoid(10),
        )
      },
      test("saturates near 1.0 for large positive input") {
        assertTrue(TexelTuner.sigmoid(50) > 0.9999)
      },
      test("saturates near 0.0 for large negative input") {
        assertTrue(TexelTuner.sigmoid(-50) < 0.0001)
      },
    ),
    suite("totalLoss")(
      test("returns 0 for an empty sample list") {
        assertTrue(TexelTuner.totalLoss(Nil, Map("pawn" -> 100), K) == 0.0)
      },
      test("decreases as the prediction approaches the actual outcome") {
        // One sample: 1 pawn advantage, white actually won (outcome=1).
        val sample = TexelTuner.Sample(Map("pawn" -> 1), outcome = 1.0)
        val highLoss = TexelTuner.totalLoss(Seq(sample), Map("pawn" -> 0),   K)
        val lowLoss  = TexelTuner.totalLoss(Seq(sample), Map("pawn" -> 200), K)
        assertTrue(lowLoss < highLoss)
      },
    ),
    suite("tune")(
      test("converges from a low initial guess toward the true weight") {
        // Synthetic corpus: every sample has 1 pawn imbalance + the
        // outcome is determined by a "true" 100 cp pawn value via the
        // same sigmoid. Tuner should pull pawn → ~100.
        val truePawn = 100
        val samples = Seq(
          TexelTuner.Sample(Map("pawn" ->  1), outcome = TexelTuner.sigmoid(K * truePawn)),
          TexelTuner.Sample(Map("pawn" ->  2), outcome = TexelTuner.sigmoid(K * 2 * truePawn)),
          TexelTuner.Sample(Map("pawn" ->  3), outcome = TexelTuner.sigmoid(K * 3 * truePawn)),
          TexelTuner.Sample(Map("pawn" -> -1), outcome = TexelTuner.sigmoid(K * -1 * truePawn)),
          TexelTuner.Sample(Map("pawn" -> -2), outcome = TexelTuner.sigmoid(K * -2 * truePawn)),
        )
        val result = TexelTuner.tune(samples, initial = Map("pawn" -> 50), K = K)
        // We're looking for "close to 100" — coordinate descent at
        // integer steps lands in [80, 120] reliably for this dataset.
        assertTrue(
          math.abs(result.weights("pawn") - truePawn) <= 20,
          result.finalLoss < 0.05,
        )
      },
      test("returns the initial weights unchanged when no improvement is possible") {
        // Single sample at outcome=0.5 with pawn=0 features → loss is
        // already at its minimum (sigmoid(0) = 0.5). The tuner can't
        // improve and must terminate with the initial vector.
        val samples = Seq(TexelTuner.Sample(Map("pawn" -> 0), outcome = 0.5))
        val result  = TexelTuner.tune(samples, initial = Map("pawn" -> 100), K = K)
        assertTrue(result.weights == Map("pawn" -> 100))
      },
      test("works with multiple features simultaneously") {
        // True values: pawn=100, knight=300. Outcomes generated from
        // a 2-feature linear eval + sigmoid.
        val truePawn   = 100
        val trueKnight = 300
        val raw: Seq[Map[String, Int]] = Seq(
          Map("pawn" -> 1, "knight" -> 0),
          Map("pawn" -> 0, "knight" -> 1),
          Map("pawn" -> 1, "knight" -> 1),
          Map("pawn" -> 2, "knight" -> 0),
          Map("pawn" -> 0, "knight" -> 2),
          Map("pawn" -> -1, "knight" -> 1),
          Map("pawn" -> 1, "knight" -> -1),
        )
        val samples = raw.map { features =>
          val trueEval = features.getOrElse("pawn",   0) * truePawn +
                         features.getOrElse("knight", 0) * trueKnight
          TexelTuner.Sample(features, outcome = TexelTuner.sigmoid(K * trueEval))
        }
        val result = TexelTuner.tune(
          samples,
          initial = Map("pawn" -> 50, "knight" -> 150),
          K = K,
        )
        assertTrue(
          math.abs(result.weights("pawn") - truePawn) <= 30,
          math.abs(result.weights("knight") - trueKnight) <= 40,
        )
      },
      test("terminates at maxIterations even if it could keep nudging") {
        val samples = Seq(TexelTuner.Sample(Map("pawn" -> 1), outcome = 0.7))
        val result  = TexelTuner.tune(
          samples,
          initial = Map("pawn" -> 0),
          K = K,
          maxIterations = 1,
        )
        assertTrue(result.iterations == 1)
      },
    ),
    suite("oneSweep")(
      test("picks the side (+/-) with the better loss when both are improvements") {
        // Single sample: outcome 1.0 with pawn=1. Eval should be high
        // → sigmoid → high. So increasing pawn weight helps. The
        // sweep should pick the + direction.
        val samples = Seq(TexelTuner.Sample(Map("pawn" -> 1), outcome = 1.0))
        val initial = Map("pawn" -> 0)
        val initialLoss = TexelTuner.totalLoss(samples, initial, K)
        val (next, nextLoss) = TexelTuner.oneSweep(samples, initial, K, step = 50, currentLoss = initialLoss)
        assertTrue(next("pawn") == 50, nextLoss < initialLoss)
      },
      test("leaves a weight alone if neither direction improves") {
        // The weight is already optimal for this sample → both +step
        // and -step worsen the loss → oneSweep returns the original
        // weights unchanged.
        val samples = Seq(TexelTuner.Sample(Map("pawn" -> 1), outcome = TexelTuner.sigmoid(K * 100)))
        val initial = Map("pawn" -> 100)
        val initialLoss = TexelTuner.totalLoss(samples, initial, K)
        val (next, nextLoss) = TexelTuner.oneSweep(samples, initial, K, step = 50, currentLoss = initialLoss)
        assertTrue(next == initial, nextLoss == initialLoss)
      },
    ),
  )

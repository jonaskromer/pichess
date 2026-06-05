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
        assertTrue(
          TexelTuner.evaluate(Map.empty[String, Double], Map("pawn" -> 100)) == 0.0
        )
      },
      test("computes a linear combination of feature × weight") {
        // 2 pawns × 100 + 1 knight × 300 = 500
        assertTrue(
          TexelTuner.evaluate(
            features = Map("pawn" -> 2.0, "knight" -> 1.0),
            weights  = Map("pawn" -> 100, "knight" -> 300),
          ) == 500.0
        )
      },
      test("ignores feature keys with no weight (treats them as 0)") {
        assertTrue(
          TexelTuner.evaluate(
            features = Map("pawn" -> 2.0, "queen" -> 1.0),
            weights  = Map("pawn" -> 100),
          ) == 200.0
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
        val sample = TexelTuner.Sample(Map("pawn" -> 1.0), outcome = 1.0)
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
          TexelTuner.Sample(Map("pawn" ->  1.0), outcome = TexelTuner.sigmoid(K * truePawn)),
          TexelTuner.Sample(Map("pawn" ->  2.0), outcome = TexelTuner.sigmoid(K * 2 * truePawn)),
          TexelTuner.Sample(Map("pawn" ->  3.0), outcome = TexelTuner.sigmoid(K * 3 * truePawn)),
          TexelTuner.Sample(Map("pawn" -> -1.0), outcome = TexelTuner.sigmoid(K * -1 * truePawn)),
          TexelTuner.Sample(Map("pawn" -> -2.0), outcome = TexelTuner.sigmoid(K * -2 * truePawn)),
        )
        val result = TexelTuner.tune(samples, initial = Map("pawn" -> 50), K = K)
        // We're looking for "close to 100" — coordinate descent at
        // integer steps lands in [80, 120] reliably for this dataset.
        assertTrue(
          math.abs(result.weights("pawn") - truePawn) <= 20,
          result.finalLoss < 0.05,
        )
      },
      test("returns the initial weights unchanged on an empty sample stream") {
        // With streaming input there's no upfront emptiness check;
        // tune builds a 0-sample CompiledCorpus and short-circuits.
        val result = TexelTuner.tune(Iterator.empty, initial = Map("pawn" -> 100), K = K)
        assertTrue(result.weights == Map("pawn" -> 100), result.iterations == 0)
      },
      test("tune omitting K uses the default K=0.4 (covers the default-arg branch)") {
        // Sanity check that the default-arg version is callable
        // — covers the `tune$default$3` synthetic.
        val sample = TexelTuner.Sample(Map("pawn" -> 1.0), outcome = 0.7)
        val result = TexelTuner.tune(Seq(sample), Map("pawn" -> 100))
        assertTrue(result.weights.contains("pawn"))
      },
      test("returns the initial weights unchanged when no improvement is possible") {
        // Single sample at outcome=0.5 with pawn=0 features → loss is
        // already at its minimum (sigmoid(0) = 0.5). The tuner can't
        // improve and must terminate with the initial vector.
        val samples = Seq(TexelTuner.Sample(Map("pawn" -> 0.0), outcome = 0.5))
        val result  = TexelTuner.tune(samples, initial = Map("pawn" -> 100), K = K)
        assertTrue(result.weights == Map("pawn" -> 100))
      },
      test("works with multiple features simultaneously") {
        // True values: pawn=100, knight=300. Outcomes generated from
        // a 2-feature linear eval + sigmoid.
        val truePawn   = 100
        val trueKnight = 300
        val raw: Seq[Map[String, Double]] = Seq(
          Map("pawn" -> 1.0, "knight" -> 0.0),
          Map("pawn" -> 0.0, "knight" -> 1.0),
          Map("pawn" -> 1.0, "knight" -> 1.0),
          Map("pawn" -> 2.0, "knight" -> 0.0),
          Map("pawn" -> 0.0, "knight" -> 2.0),
          Map("pawn" -> -1.0, "knight" -> 1.0),
          Map("pawn" -> 1.0, "knight" -> -1.0),
        )
        val samples = raw.map { features =>
          val trueEval = features.getOrElse("pawn",   0.0) * truePawn +
                         features.getOrElse("knight", 0.0) * trueKnight
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
        val samples = Seq(TexelTuner.Sample(Map("pawn" -> 1.0), outcome = 0.7))
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
        val samples = Seq(TexelTuner.Sample(Map("pawn" -> 1.0), outcome = 1.0))
        val initial = Map("pawn" -> 0)
        val initialLoss = TexelTuner.totalLoss(samples, initial, K)
        val (next, nextLoss) = TexelTuner.oneSweep(samples, initial, K, step = 50, currentLoss = initialLoss)
        assertTrue(next("pawn") == 50, nextLoss < initialLoss)
      },
      test("leaves a weight alone if neither direction improves") {
        // The weight is already optimal for this sample → both +step
        // and -step worsen the loss → oneSweep returns the original
        // weights unchanged.
        val samples = Seq(TexelTuner.Sample(Map("pawn" -> 1.0), outcome = TexelTuner.sigmoid(K * 100)))
        val initial = Map("pawn" -> 100)
        val initialLoss = TexelTuner.totalLoss(samples, initial, K)
        val (next, nextLoss) = TexelTuner.oneSweep(samples, initial, K, step = 50, currentLoss = initialLoss)
        assertTrue(next == initial, nextLoss == initialLoss)
      },
    ),
    suite("cached `tune` equivalence with the reference oneSweep")(
      test("multi-feature fixture: cached + reference paths produce identical results") {
        // Pin the optimized `tune` (array-indexed, cached dot product
        // + cached diff) against a hand-rolled coordinate-descent
        // loop driving the reference [[TexelTuner.oneSweep]]. If the
        // cache introduces any drift or off-by-one in the
        // incremental update math, this test catches it.
        //
        // The fixture mixes feature scales: pawn / knight / queen as
        // material; tempo as a side-to-move bit; a few "PST-like"
        // sparse features (only some samples carry them). Five
        // samples, each with a different combination — enough to
        // exercise the inverted-index code paths but small enough
        // that the reference fold runs cheaply.
        val initial = Map(
          "pawn"     -> 50,
          "knight"   -> 150,
          "queen"    -> 400,
          "tempo"    -> 5,
          "pst_a2"   -> 0,
          "pst_e4"   -> 0,
        )
        val samples = Seq(
          TexelTuner.Sample(
            Map("pawn" -> 1.0, "knight" -> 0.0, "queen" -> 0.0, "tempo" -> 1.0, "pst_a2" -> 1.0),
            outcome = 0.7,
          ),
          TexelTuner.Sample(
            Map("pawn" -> 0.0, "knight" -> 1.0, "queen" -> 0.0, "tempo" -> 1.0, "pst_e4" -> 1.0),
            outcome = 0.6,
          ),
          TexelTuner.Sample(
            Map("pawn" -> 2.0, "knight" -> -1.0, "queen" -> 1.0, "tempo" -> 1.0),
            outcome = 1.0,
          ),
          TexelTuner.Sample(
            Map("pawn" -> -1.0, "knight" -> 0.0, "queen" -> 0.0, "tempo" -> 1.0, "pst_a2" -> -1.0),
            outcome = 0.2,
          ),
          TexelTuner.Sample(
            Map("pawn" -> 0.0, "knight" -> 0.0, "queen" -> -1.0, "tempo" -> 1.0, "pst_e4" -> 1.0),
            outcome = 0.3,
            weight  = 0.7,  // exercise the source-quality weighting
          ),
        )

        val cached = TexelTuner.tune(samples, initial, K = K, maxIterations = 8, initialStep = 16)

        // Reference: drive `oneSweep` directly with the exact same
        // halving schedule the cached path uses.
        val reference = referenceTune(
          samples,
          initial,
          K = K,
          maxIterations = 8,
          initialStep = 16,
        )

        assertTrue(
          cached.weights == reference.weights,
          cached.iterations == reference.iterations,
          // FP epsilon: the cached path uses incremental sumSq + a
          // periodic rebuild, the reference recomputes totalLoss from
          // scratch each pass. After 8 iterations the difference is
          // bounded by ~1e-12 in practice.
          math.abs(cached.finalLoss - reference.finalLoss) < 1e-9,
        )
      },
    ),
  )

  /** Hand-rolled coordinate descent that calls the reference
    * [[TexelTuner.oneSweep]] each pass. Used to pin the cached
    * [[TexelTuner.tune]] path. */
  private def referenceTune(
      samples: Seq[TexelTuner.Sample],
      initial: Map[String, Int],
      K: Double,
      maxIterations: Int,
      initialStep: Int,
  ): TexelTuner.TuningResult =
    var weights = initial
    var step    = initialStep
    var iters   = 0
    var loss    = TexelTuner.totalLoss(samples, weights, K)
    while step >= 1 && iters < maxIterations do
      iters += 1
      val (next, nextLoss) = TexelTuner.oneSweep(samples, weights, K, step, loss)
      if nextLoss < loss then
        weights = next
        loss = nextLoss
      else
        step = step / 2
    TexelTuner.TuningResult(weights, loss, iters)

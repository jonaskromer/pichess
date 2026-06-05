package chess.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import chess.bot.engine.TaperedFeatureExtractor
import chess.bot.train.TexelTuner
import chess.codec.FenParserRegex

/** Microbenchmarks for the Texel coordinate-descent tuner.
  *
  * `tune` is the offline-training inner loop. Each "sweep" iterates
  * every weight and tries ± a step — for the tapered feature space
  * (~690 weights) on a corpus of N samples that's `2 × 690 × N`
  * sigmoid evaluations per sweep, and the tuner runs roughly 10–20
  * sweeps before convergence. So total training cost is dominated by
  * sample count × weight count × eval-cost-per-sample.
  *
  * The benches isolate three things:
  *   1. `evaluate` — the dot product over (Map[String, Double] features)
  *      × (Map[String, Int] weights), called twice per sweep × weight.
  *   2. `totalLoss` — the full-pass sigmoid-and-square over all samples
  *      at a fixed weight vector. The natural unit of work.
  *   3. `oneSweep` — one full coordinate-descent pass: this is the
  *      number a training-time forecast actually wants. Multiply by
  *      ~15 to estimate convergence cost on the same sample shape.
  *
  * Two feature scales are exercised:
  *   - 5 features (just the material seed) — synthetic baseline.
  *   - 690 features (tapered full extractor) — production training shape.
  *
  * Sample counts (1k, 10k) are picked so the bench finishes in
  * reasonable wall-clock; the prod corpus is several million rows, so
  * extrapolation is linear in N.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class TexelTunerBenchmark:

  // Seed for the synthetic feature generator. Fixed so runs are
  // reproducible across JMH invocations (no flakiness).
  private val rng = new java.util.Random(0xC0FFEEL)

  // Baseline (5 material features only) — toy size, mostly measures
  // sigmoid + Map[String, Int] iteration cost.
  private val matFeatureKeys: List[String] =
    List("pawn", "knight", "bishop", "rook", "queen")
  private val matWeights: Map[String, Int] =
    Map("pawn" -> 100, "knight" -> 320, "bishop" -> 330, "rook" -> 500, "queen" -> 900)

  // Realistic — tapered full extractor's 690 features against a
  // matching seed-weights vector. Use the actual extractor over a real
  // game state so the feature shape mirrors what the tuner sees.
  private val taperedWeights: Map[String, Int] =
    TaperedFeatureExtractor.defaultSeedWeights
  private val sampleState =
    UnsafeRuntime.run(
      FenParserRegex.parse(
        "r1bqkb1r/1ppp1ppp/p1n2n2/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5"
      )
    )

  // Generate `n` samples by perturbing the realistic feature counts
  // with small integer noise. Outcomes drawn from a sigmoid of the
  // "true" eval so the loss surface has a real minimum to descend to —
  // matches the convergence test in TexelTunerSpec.
  private def synthMaterial(n: Int): Seq[TexelTuner.Sample] =
    (0 until n).map { _ =>
      val features = matFeatureKeys.map { k =>
        k -> rng.nextInt(5).toDouble
      }.toMap
      val trueEval = features.iterator.map { case (k, v) =>
        v * matWeights.getOrElse(k, 0)
      }.sum
      TexelTuner.Sample(features, outcome = TexelTuner.sigmoid(0.4 * trueEval))
    }

  // For the tapered shape, derive `n` realistic feature maps from the
  // actual extractor — same key set, integer values like the prod
  // pipeline. Outcomes follow the seed weights so the tuner has a
  // recoverable signal.
  private def synthTapered(n: Int): Seq[TexelTuner.Sample] =
    val baseFeatures = TaperedFeatureExtractor.full.features(sampleState)
    (0 until n).map { _ =>
      val features = baseFeatures.map { case (k, v) =>
        // Multiplicative jitter in [-1, +1] keeps every feature an
        // integer-ish double; the tuner doesn't care about the exact
        // distribution, only that the loss surface is non-degenerate.
        k -> (v + rng.nextInt(3) - 1)
      }
      val trueEval = features.iterator.map { case (k, v) =>
        v * taperedWeights.getOrElse(k, 0)
      }.sum
      TexelTuner.Sample(features, outcome = TexelTuner.sigmoid(0.4 * trueEval))
    }

  private val mat1k       = synthMaterial(1000)
  private val mat10k      = synthMaterial(10_000)
  private val tapered1k   = synthTapered(1000)
  private val tapered10k  = synthTapered(10_000)

  // ---- evaluate (the inner dot product) -------------------------------

  @Benchmark
  def evaluateMaterial: Double =
    TexelTuner.evaluate(mat1k.head.features, matWeights)

  @Benchmark
  def evaluateTapered: Double =
    TexelTuner.evaluate(tapered1k.head.features, taperedWeights)

  // ---- totalLoss (one full pass over the dataset) --------------------

  @Benchmark
  def totalLossMaterial1k: Double =
    TexelTuner.totalLoss(mat1k, matWeights, K = 0.4)

  @Benchmark
  def totalLossMaterial10k: Double =
    TexelTuner.totalLoss(mat10k, matWeights, K = 0.4)

  @Benchmark
  def totalLossTapered1k: Double =
    TexelTuner.totalLoss(tapered1k, taperedWeights, K = 0.4)

  @Benchmark
  def totalLossTapered10k: Double =
    TexelTuner.totalLoss(tapered10k, taperedWeights, K = 0.4)

  // ---- oneSweep (one coord-descent pass = ~2 totalLoss × weightCount)

  @Benchmark
  def oneSweepMaterial1k: (Map[String, Int], Double) =
    val initialLoss = TexelTuner.totalLoss(mat1k, matWeights, K = 0.4)
    TexelTuner.oneSweep(mat1k, matWeights, K = 0.4, step = 16, currentLoss = initialLoss)

  @Benchmark
  def oneSweepTapered1k: (Map[String, Int], Double) =
    val initialLoss = TexelTuner.totalLoss(tapered1k, taperedWeights, K = 0.4)
    TexelTuner.oneSweep(tapered1k, taperedWeights, K = 0.4, step = 16, currentLoss = initialLoss)

package chess.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import chess.bot.engine.{ArrayTaperedEvaluator, Evaluator, Search, TaperedFeatureExtractor}
import chess.bot.train.SelfPlay

/** Throughput benchmarks for [[SelfPlay.round]] — measures how
  * cross-game parallelism scales for the self-play training loop.
  *
  * Each round plays N games with `champion` vs `challenger`,
  * alternating colors. With parallelism > 1 the games run
  * concurrently via ZIO fibers, sharing the same `Search`
  * instances (so the transposition table benefits from cross-
  * game warming). Independent `GameState` per game keeps the
  * search-side correctness intact.
  *
  * Game-count + depth are tuned so a single sequential round
  * finishes in a few hundred ms — small enough to fit a JMH
  * iteration, large enough to dwarf fiber-spawn overhead.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class SelfPlayBenchmark:

  private val taperedEval: Evaluator =
    ArrayTaperedEvaluator(TaperedFeatureExtractor.defaultSeedWeights)

  // Two distinct Search instances — champion vs challenger. In a
  // training run these would have different weight snapshots; for
  // the bench they're identical (same evaluator), which is fine
  // because we're measuring round wall-clock, not playing strength.
  private val champion:   Search = Search.alphaBeta(taperedEval)
  private val challenger: Search = Search.alphaBeta(taperedEval)

  // Bench parameters — kept small so a serial round finishes
  // within one JMH iteration (~1 s). Depth 2 + 40-ply cap is the
  // sweet spot: long enough that games hit varied positions, short
  // enough not to dominate the bench run.
  private inline val Games    = 4
  private inline val Depth    = 2
  private inline val MaxPlies = 40

  // ── Serial baseline ─────────────────────────────────────────────

  @Benchmark
  def roundSerial: SelfPlay.RoundResult =
    UnsafeRuntime.run(
      SelfPlay.round(champion, challenger, Games, Depth, MaxPlies, parallelism = 1)
    )

  // ── Parallel variants ───────────────────────────────────────────

  @Benchmark
  def roundParallel2: SelfPlay.RoundResult =
    UnsafeRuntime.run(
      SelfPlay.round(champion, challenger, Games, Depth, MaxPlies, parallelism = 2)
    )

  @Benchmark
  def roundParallel4: SelfPlay.RoundResult =
    UnsafeRuntime.run(
      SelfPlay.round(champion, challenger, Games, Depth, MaxPlies, parallelism = 4)
    )

  @Benchmark
  def roundParallel8: SelfPlay.RoundResult =
    UnsafeRuntime.run(
      SelfPlay.round(champion, challenger, Games, Depth, MaxPlies, parallelism = 8)
    )

  // ── Larger round (16 games) to confirm scaling holds ────────────

  private inline val LargeGames = 16

  @Benchmark
  def largeRoundSerial: SelfPlay.RoundResult =
    UnsafeRuntime.run(
      SelfPlay.round(champion, challenger, LargeGames, Depth, MaxPlies, parallelism = 1)
    )

  @Benchmark
  def largeRoundParallel4: SelfPlay.RoundResult =
    UnsafeRuntime.run(
      SelfPlay.round(champion, challenger, LargeGames, Depth, MaxPlies, parallelism = 4)
    )

  @Benchmark
  def largeRoundParallel8: SelfPlay.RoundResult =
    UnsafeRuntime.run(
      SelfPlay.round(champion, challenger, LargeGames, Depth, MaxPlies, parallelism = 8)
    )

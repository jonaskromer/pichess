package chess.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import chess.bot.engine.{Evaluator, FeatureExtractor, Search, TaperedEvaluator, TaperedFeatureExtractor}
import chess.codec.FenParserRegex
import chess.model.board.{GameState, Move}

/** Microbenchmarks for [[Search.bestMove]] across representative
  * positions and depths.
  *
  * The aim is to surface where the bot spends its time in the
  * search loop — material vs tapered evaluator (does the per-leaf
  * eval cost matter), starting vs mid-game vs Kiwipete (does the
  * legal-move count dominate), depth 2 vs 3 vs 4 (does the α-β tree
  * scale roughly as expected, ~6× per ply with no killers/MVV-LVA).
  *
  * Notes on fixtures:
  *   - Two evaluator profiles: material-only (cheap, ~6 popcounts per
  *     leaf) and tapered-tuned (full 690-feature extractor, the
  *     production path). Comparing these directly says how much of
  *     search time is eval vs the rest.
  *   - Each `(eval, position, depth)` benchmark constructs a fresh
  *     [[Search]] at class-init so the TT is empty when measured. That
  *     keeps the bench comparable across runs even though the engine
  *     normally reuses the TT across moves.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class SearchBenchmark:

  private val startingState = BenchFixtures.startingState
  private val midGameState  = BenchFixtures.midGameState
  // Kiwipete — high-attack-density middlegame with castling rights, en
  // passant, multiple attackers per square. Standard perft fixture; in
  // a search context it produces wide branching at every node.
  private val kiwiState: GameState =
    UnsafeRuntime.run(
      FenParserRegex.parse(
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
      )
    )

  // Tapered evaluator with seed (un-tuned) weights — closer to the
  // production cost than material-only without depending on a tuned
  // snapshot landing in the bench classpath. The feature extractor
  // still does the full ~690-feature pass at every leaf.
  private val taperedEval: Evaluator =
    TaperedEvaluator(
      TaperedFeatureExtractor.defaultSeedWeights,
      FeatureExtractor.full,
    )

  // Each `bestMove` benchmark creates a fresh [[Search]] inside the
  // body. The first invocation populates the transposition table; if
  // we held a single Search across invocations the TT would memoise
  // the result and depth 2 / 3 / 4 would all collapse to the same
  // (cached) time. Construction is cheap (TT alloc + book empty), so
  // including it in the per-op time is acceptable.
  private def freshSearch(eval: Evaluator): Search = Search.alphaBeta(eval)

  // ----- material-only evaluator ---------------------------------------

  @Benchmark
  def materialDepth2Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(Evaluator.materialOnly).bestMove(startingState, depth = 2))

  @Benchmark
  def materialDepth3Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(Evaluator.materialOnly).bestMove(startingState, depth = 3))

  @Benchmark
  def materialDepth4Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(Evaluator.materialOnly).bestMove(startingState, depth = 4))

  @Benchmark
  def materialDepth3MidGame: Option[Move] =
    UnsafeRuntime.run(freshSearch(Evaluator.materialOnly).bestMove(midGameState, depth = 3))

  @Benchmark
  def materialDepth3KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshSearch(Evaluator.materialOnly).bestMove(kiwiState, depth = 3))

  // ----- tapered evaluator (production-shape per-leaf cost) -----------

  @Benchmark
  def taperedDepth2Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(taperedEval).bestMove(startingState, depth = 2))

  @Benchmark
  def taperedDepth3Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(taperedEval).bestMove(startingState, depth = 3))

  @Benchmark
  def taperedDepth4Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(taperedEval).bestMove(startingState, depth = 4))

  @Benchmark
  def taperedDepth3MidGame: Option[Move] =
    UnsafeRuntime.run(freshSearch(taperedEval).bestMove(midGameState, depth = 3))

  @Benchmark
  def taperedDepth3KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshSearch(taperedEval).bestMove(kiwiState, depth = 3))

  // ----- isolated evaluator cost ---------------------------------------
  // Pull the eval out of the search to see how much of search time is
  // the leaf evaluator vs everything else (move gen, TT, α-β bookkeeping).

  @Benchmark
  def evalMaterialStart: Int =
    Evaluator.materialOnly.evaluate(startingState)

  @Benchmark
  def evalMaterialKiwiPete: Int =
    Evaluator.materialOnly.evaluate(kiwiState)

  @Benchmark
  def evalTaperedStart: Int =
    taperedEval.evaluate(startingState)

  @Benchmark
  def evalTaperedKiwiPete: Int =
    taperedEval.evaluate(kiwiState)

  // ----- feature extractor cost (the eval's hot inner loop) -----------
  // The tapered extractor builds the full 690-key map per call. This
  // bench isolates that allocation + iteration cost from the dot-product.

  @Benchmark
  def featuresFullStart: Map[String, Int] =
    FeatureExtractor.full.features(startingState)

  @Benchmark
  def featuresFullKiwiPete: Map[String, Int] =
    FeatureExtractor.full.features(kiwiState)

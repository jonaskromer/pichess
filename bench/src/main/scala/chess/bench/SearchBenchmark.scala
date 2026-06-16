package chess.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import chess.bot.engine.{ArrayTaperedEvaluator, Evaluator, FeatureExtractor, HybridEvaluator, Search, TaperedEvaluator, TaperedFeatureExtractor, WeightsLoader}
import chess.bot.train.SelfPlay
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
  //
  // Two flavours: `taperedEval` is the Map-backed [[TaperedEvaluator]]
  // (the historical impl, still used for comparison + tests);
  // `arrayTaperedEval` is the array-backed [[ArrayTaperedEvaluator]]
  // wired into EngineBundle. Bench both so we can see the speedup.
  private val seedWeights: Map[String, Int] =
    TaperedFeatureExtractor.defaultSeedWeights

  private val taperedEval: Evaluator =
    TaperedEvaluator(seedWeights, FeatureExtractor.full)

  private val arrayTaperedEval: Evaluator =
    ArrayTaperedEvaluator(seedWeights)

  // ----- production hybrid (HCE v8 + baked NNUE, α 0.3→0.5) -------------
  // The REAL deployed eval: ArrayTaperedEvaluator(v8) blended with the baked
  // NNUE via HybridEvaluator. The HCE-only benches above miss the NNUE
  // accumulator/output cost; these profile the actual bot. `freshSearch`
  // defaults to incrementalAccumulators=true at parallelism 1 → the production
  // incremental-NNUE path (only the NNUE is incremental; the HCE half is
  // recomputed per node).
  private val v8Weights: Map[String, Int] =
    UnsafeRuntime.run(WeightsLoader.load(8)).weights
  private val bakedNnue: Evaluator =
    chess.bot.engine.nnue.NnueEvaluator.loadResource("/nnue-v1.bin").get
  private val hybridEval: Evaluator =
    HybridEvaluator(ArrayTaperedEvaluator(v8Weights), bakedNnue, 0.3, 0.5)

  @Benchmark
  def evalNnueStart: Int = bakedNnue.evaluate(startingState)

  @Benchmark
  def evalHybridStart: Int = hybridEval.evaluate(startingState)

  @Benchmark
  def evalHybridKiwiPete: Int = hybridEval.evaluate(kiwiState)

  @Benchmark
  def hybridDepth4Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(hybridEval).bestMove(startingState, depth = 4))

  @Benchmark
  def hybridDepth4KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshSearch(hybridEval).bestMove(kiwiState, depth = 4))

  @Benchmark
  def hybridDepth5Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(hybridEval).bestMove(startingState, depth = 5))

  // ----- deep sweep (depth 6/7) ----------------------------------------
  // The production search reaches ~d6-8 at the live budget, so these are the
  // realistic bottleneck picture. Hybrid vs HCE-only at the SAME depth
  // isolates the NNUE's marginal per-node cost as the tree grows.
  @Benchmark
  def hybridDepth6Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(hybridEval).bestMove(startingState, depth = 6))

  // (arrayTaperedDepth6Start already exists in the HCE section below — reused
  // for the hybrid-vs-HCE depth-6 comparison.)

  @Benchmark
  def hybridDepth6KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshSearch(hybridEval).bestMove(kiwiState, depth = 6))

  @Benchmark
  def arrayTaperedDepth6KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(kiwiState, depth = 6))

  @Benchmark
  def hybridDepth7Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(hybridEval).bestMove(startingState, depth = 7))

  @Benchmark
  def arrayTaperedDepth7Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(startingState, depth = 7))

  // Representative "many games at depth 6" workload: full hybrid self-play
  // games (varied openings → middlegames → endgames), the production eval.
  // The target for CPU / allocation profiling — exercises positions a single
  // fixed-position search never reaches (esp. endgames).
  @Benchmark
  @OutputTimeUnit(TimeUnit.SECONDS)
  def hybridSelfPlayDepth6: SelfPlay.RoundResult =
    UnsafeRuntime.run(
      SelfPlay.round(freshSearch(hybridEval), freshSearch(hybridEval),
        games = 6, depth = 6, maxPlies = 80, parallelism = 1))

  // eval-cache (CachedEvaluator, the _EVCACHE lever) over the hybrid. Fresh
  // cache per op (like the fresh TT) so the hit-rate reflects intra-search
  // transpositions, not a stale cross-op cache. Caches the leaf eval
  // (HCE + NNUE evaluateFrom); the per-node applyDiff accumulator update is
  // unavoidable. Compare to the uncached hybrid benches above.
  @Benchmark
  def cachedHybridDepth4Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(chess.bot.engine.CachedEvaluator.of(hybridEval)).bestMove(startingState, depth = 4))

  @Benchmark
  def cachedHybridDepth4KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshSearch(chess.bot.engine.CachedEvaluator.of(hybridEval)).bestMove(kiwiState, depth = 4))

  @Benchmark
  def cachedHybridDepth5Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(chess.bot.engine.CachedEvaluator.of(hybridEval)).bestMove(startingState, depth = 5))

  // ----- NNUE accumulator-update headroom probe ------------------------
  // applyDiff (per-node accumulator maintenance) is the dominant NNUE cost
  // in the incremental search — fusing evaluateFrom barely moved the search
  // time, so the accumulator update, not the output layer, is the cost.
  // This isolates one make+unmake of a quiet pawn move (e2e4): 4 column ±s ×
  // 128 ints per applyDiff. ns/op reveals whether the element-wise int-add
  // loop is auto-vectorized by C2 (~tens of ns → tapped) or running scalar
  // (~hundreds of ns → Vector-API SIMD headroom). Returns an accumulator
  // element to defeat dead-code elimination.
  private val nnueNet: chess.bot.engine.nnue.NnueEvaluator =
    chess.bot.engine.nnue.NnueEvaluator.loadResource("/nnue-v1.bin").get
  private val afterE4State: GameState =
    UnsafeRuntime.run(
      FenParserRegex.parse(
        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      )
    )
  private val diffAcc: chess.bot.engine.nnue.NnueAccumulator =
    val a = nnueNet.freshAccumulator()
    nnueNet.refreshInto(a, startingState.board)
    a

  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def applyDiffMakeUnmake: Int =
    nnueNet.applyDiff(diffAcc, startingState.board, afterE4State.board)
    nnueNet.applyDiff(diffAcc, afterE4State.board, startingState.board)
    diffAcc.white(0) + diffAcc.black(0)

  // Each `bestMove` benchmark creates a fresh [[Search]] inside the
  // body. The first invocation populates the transposition table; if
  // we held a single Search across invocations the TT would memoise
  // the result and depth 2 / 3 / 4 would all collapse to the same
  // (cached) time. Construction is cheap (TT alloc + book empty), so
  // including it in the per-op time is acceptable.
  private def freshSearch(eval: Evaluator): Search = Search.alphaBeta(eval)

  // For parallel benches: use 4 fibers by default (target multi-core
  // machines; JMH bench host has 8+ cores so 4 leaves headroom for
  // the bench's measurement thread).
  private def freshParSearch(eval: Evaluator, par: Int = 4): Search =
    Search.alphaBeta(eval, parallelism = par)

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

  // ----- array tapered evaluator (zero-alloc hot path) -----------------

  @Benchmark
  def arrayTaperedDepth3Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(startingState, depth = 3))

  @Benchmark
  def arrayTaperedDepth4Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(startingState, depth = 4))

  @Benchmark
  def arrayTaperedDepth3MidGame: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(midGameState, depth = 3))

  @Benchmark
  def arrayTaperedDepth3KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(kiwiState, depth = 3))

  @Benchmark
  def arrayTaperedDepth4KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(kiwiState, depth = 4))

  @Benchmark
  def arrayTaperedDepth5Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(startingState, depth = 5))

  // ----- parallel root search (LazySMP-style fan-out) ------------------

  @Benchmark
  def arrayTaperedParDepth4Start: Option[Move] =
    UnsafeRuntime.run(freshParSearch(arrayTaperedEval).bestMove(startingState, depth = 4))

  @Benchmark
  def arrayTaperedParDepth5Start: Option[Move] =
    UnsafeRuntime.run(freshParSearch(arrayTaperedEval).bestMove(startingState, depth = 5))

  @Benchmark
  def arrayTaperedParDepth4KiwiPete: Option[Move] =
    UnsafeRuntime.run(freshParSearch(arrayTaperedEval).bestMove(kiwiState, depth = 4))

  @Benchmark
  def arrayTaperedParDepth3MidGame: Option[Move] =
    UnsafeRuntime.run(freshParSearch(arrayTaperedEval).bestMove(midGameState, depth = 3))

  @Benchmark
  def arrayTaperedDepth6Start: Option[Move] =
    UnsafeRuntime.run(freshSearch(arrayTaperedEval).bestMove(startingState, depth = 6))

  @Benchmark
  def arrayTaperedParDepth6Start: Option[Move] =
    UnsafeRuntime.run(freshParSearch(arrayTaperedEval).bestMove(startingState, depth = 6))

  @Benchmark
  def arrayTaperedPar2Depth5Start: Option[Move] =
    UnsafeRuntime.run(freshParSearch(arrayTaperedEval, par = 2).bestMove(startingState, depth = 5))

  @Benchmark
  def arrayTaperedPar2Depth6Start: Option[Move] =
    UnsafeRuntime.run(freshParSearch(arrayTaperedEval, par = 2).bestMove(startingState, depth = 6))

  @Benchmark
  def evalArrayTaperedStart: Int =
    arrayTaperedEval.evaluate(startingState)

  @Benchmark
  def evalArrayTaperedKiwiPete: Int =
    arrayTaperedEval.evaluate(kiwiState)

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

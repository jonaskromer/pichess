package chess.bot.engine

import zio.*

import chess.bot.engine.nnue.{NnueEnsemble, NnueEvaluator}

/** All-in-one engine bootstrap.
  *
  * The bot's runtime is two pieces of data — weights + opening book —
  * plus the immutable code. [[EngineBundle]] is the single call that
  * loads both from committed classpath resources and assembles a
  * ready-to-use [[Search]]. No DB, no network, no external paths.
  *
  * Typical use from a bot main:
  * {{{
  *   val bundle = EngineBundle.fromResources()
  *   bundle.flatMap { b =>
  *     b.search.bestMove(state, depth = 4)
  *   }
  * }}}
  *
  * A second factory ([[fromResourcesOrFallback]]) returns the
  * material-only engine if either resource fails to load — useful
  * for tooling that needs to keep working when a bad weights snapshot
  * is checked in by mistake.
  */
final case class EngineBundle(
    weights: WeightSnapshot,
    openingBook: OpeningBook,
    search: Search,
)

object EngineBundle:

  /** Which evaluator to use under the search. [[Hybrid]] is the
    * default (it measured +74 Elo vs pure HCE); the other variants
    * opt in. All non-HCE variants fall back to pure HCE when their
    * NNUE resource is missing, so the bundle always loads. */
  enum EvalSource:
    /** Hand-crafted eval — the array-backed tapered evaluator over
      * the tuned `weights/v{n}.json` snapshot. Same evaluator that
      * has been running in tournaments to date. */
    case Hce
    /** Single NNUE network loaded from `/nnue-v1.bin`. Falls back to
      * HCE if the resource is missing. */
    case Nnue
    /** NNUE ensemble — `/nnue-ens-v1-s{1..k}.bin`. K members average
      * their evals (variance reduction). Falls back to single NNUE
      * then HCE if any member is missing. */
    case NnueEns
    /** HCE + single-NNUE blend (see [[HybridEvaluator]]), mixed at
      * `hybridAlpha`. Counter-intuitively the strongest option in
      * head-to-head testing: even though our NNUE *alone* is ~−55
      * Elo vs the HCE, a ~50/50 blend beats pure HCE by ~+74 Elo at
      * depth 4 (the NNUE adds decorrelated positional signal while
      * the HCE smooths its occasional blunders). Falls back to pure
      * HCE if the NNUE resource is missing. */
    case Hybrid

  /** Default bundle: load `weights/v1.json` + `openings/main-lines.pgn`
    * from the classpath, assemble [[Search]] over those. Fails fast
    * if either resource is missing or malformed — bot startup
    * shouldn't silently continue with a half-loaded engine.
    *
    * Eval-source choice + eval cache are opt-in: defaults match the
    * historical HCE-no-cache behaviour so existing benches stay
    * apples-to-apples until callers explicitly switch. */
  def fromResources(
      weightsVersion: Int = 1,
      maxBookPly: Int = 24,
      maxTtEntries: Int = 1_000_000,
      evalSource: EvalSource = EvalSource.Hybrid,
      evalCacheEnabled: Boolean = false,
      evalCacheEntries: Int = 1_000_000,
      ensembleSize: Int = 3,
      tablebaseOracle: Option[Search] = None,
      tablebasePieceLimit: Int = 5,
      endgameHeuristicsEnabled: Boolean = false,
      // Mixing weight on the NNUE when `evalSource = Hybrid`. 0.5 is
      // the empirical optimum (depth-4 A/B: +74 Elo vs pure HCE,
      // broad plateau over 0.3–0.5).
      hybridAlpha: Double = 0.5,
  ): IO[Throwable, EngineBundle] =
    for
      weights <- WeightsLoader.load(weightsVersion)
      book    <- OpeningBookLoader.loadDefault(maxBookPly)
      // Tapered runtime evaluator: array-backed, zero-allocation on
      // the search hot loop. Weight `_mg` / `_eg` lookups + the
      // legacy un-suffixed fallback happen once at construction so
      // the per-eval cost is a single while over `Array[Int]`. Same
      // scoring as [[TaperedEvaluator]] (pinned by spec) but ~10×
      // cheaper per call — the Map[String, Int] feature path was the
      // dominant allocator (~121 MB/op at depth 4).
      hce      = ArrayTaperedEvaluator(weights.weights)
      eval     = wrapEval(
                   hce, evalSource, evalCacheEnabled, evalCacheEntries,
                   ensembleSize, endgameHeuristicsEnabled, hybridAlpha,
                 )
      base     = Search.alphaBeta(eval, book, maxTtEntries)
      search   = tablebaseOracle match
                   case Some(tb) => new TbAugmentedSearch(base, tb, tablebasePieceLimit)
                   case None     => base
    yield EngineBundle(weights, book, search)

  /** Pick the requested evaluator with NNUE fallbacks, then layer
    * the optional endgame patch + Zobrist-keyed cache decorators. */
  private def wrapEval(
      hce: Evaluator,
      source: EvalSource,
      cacheEnabled: Boolean,
      cacheEntries: Int,
      ensembleSize: Int,
      endgameHeuristics: Boolean,
      hybridAlpha: Double,
  ): Evaluator =
    val chosen = source match
      case EvalSource.Hce => hce
      case EvalSource.Nnue =>
        NnueEvaluator.loadResource("/nnue-v1.bin").getOrElse(hce)
      case EvalSource.NnueEns =>
        NnueEnsemble.loadBaked(ensembleSize)
          .map(_.asInstanceOf[Evaluator])
          .orElse(NnueEvaluator.loadResource("/nnue-v1.bin"))
          .getOrElse(hce)
      case EvalSource.Hybrid =>
        // HCE + NNUE blend. Falls back to pure HCE when the NNUE
        // resource is absent (no blend partner → nothing to mix).
        NnueEvaluator.loadResource("/nnue-v1.bin")
          .map(nnue => new HybridEvaluator(hce, nnue, hybridAlpha))
          .getOrElse(hce)
    val withEndgame =
      if endgameHeuristics then new EndgameAwareEvaluator(chosen) else chosen
    if cacheEnabled then CachedEvaluator.of(withEndgame, cacheEntries) else withEndgame

  /** Same as [[fromResources]] but on any failure, falls back to the
    * material-only evaluator + empty opening book. Returns the bundle
    * along with the failure (if any) so the caller can log it. */
  def fromResourcesOrFallback(
      weightsVersion: Int = 1,
      maxBookPly: Int = 24,
      maxTtEntries: Int = 1_000_000,
      evalSource: EvalSource = EvalSource.Hybrid,
      evalCacheEnabled: Boolean = false,
      evalCacheEntries: Int = 1_000_000,
      ensembleSize: Int = 3,
      endgameHeuristicsEnabled: Boolean = false,
      hybridAlpha: Double = 0.5,
  ): UIO[(EngineBundle, Option[Throwable])] =
    fromResources(
      weightsVersion, maxBookPly, maxTtEntries,
      evalSource, evalCacheEnabled, evalCacheEntries, ensembleSize,
      endgameHeuristicsEnabled = endgameHeuristicsEnabled,
      hybridAlpha = hybridAlpha,
    )
      .map(b => (b, None))
      .catchAll { err =>
        ZIO.succeed(
          (
            EngineBundle(
              weights     = fallbackSnapshot,
              openingBook = OpeningBook.Empty,
              // Same array-backed tapered eval as the success path —
              // the fallback bundle still benefits from tapered
              // weights if a live WeightsRepo later attaches a tuned
              // snapshot with `_mg` / `_eg` keys.
              search      = Search.alphaBeta(
                wrapEval(
                  ArrayTaperedEvaluator(fallbackSnapshot.weights),
                  evalSource, evalCacheEnabled, evalCacheEntries,
                  ensembleSize, endgameHeuristicsEnabled, hybridAlpha,
                ),
                OpeningBook.Empty,
                maxTtEntries,
              ),
            ),
            Some(err),
          )
        )
      }

  /** Material-only seed weights, used as the in-code fallback when
    * the JSON resource is unreadable. Mirrors the hand-coded values
    * in [[MaterialEvaluator]]. */
  private val fallbackSnapshot: WeightSnapshot = WeightSnapshot(
    version = 0,
    weights = Map(
      "pawn"   -> 100,
      "knight" -> 320,
      "bishop" -> 330,
      "rook"   -> 500,
      "queen"  -> 900,
    ),
  )

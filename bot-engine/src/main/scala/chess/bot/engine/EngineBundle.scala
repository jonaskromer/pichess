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
    // The assembled evaluator (immutable, thread-safe to share) — lets callers
    // build fresh, ISOLATED `Search` instances per game (own TT/tables) while
    // reusing the one loaded net, e.g. for concurrent play + LazySMP.
    eval: Evaluator,
)

object EngineBundle:

  /** Which evaluator to use under the search. [[Hybrid]] is the
    * default (it measured +424 Elo vs pure HCE with the Stockfish-
    * distilled net); the other variants opt in. All non-HCE variants
    * fall back to pure HCE when their NNUE resource is missing, so
    * the bundle always loads. */
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
      * `hybridAlpha` (≈0.3). The strongest option: the Stockfish-
      * distilled NNUE is +17 Elo vs HCE standalone, and a 0.3 blend
      * (HCE backbone + NNUE correction) beats pure HCE by ~+424 Elo
      * at depth 4. Falls back to pure HCE if the NNUE resource is
      * missing. */
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
      // Mixing weight on the NNUE when `evalSource = Hybrid`. With the
      // Stockfish-distilled net (commit c6849ec) the depth-4 A/B
      // optimum is α≈0.3 (+424 Elo vs pure HCE; broad strong plateau
      // 0.3–0.5). A smaller NNUE correction on the HCE backbone beats
      // a heavier blend even though the NNUE is now strong standalone.
      hybridAlpha: Double = 0.3,
      // Endgame NNUE weight: α tapers `hybridAlpha` (opening) → this (bare
      // endgame) by game phase ([[HybridEvaluator]] / [[GamePhase]]). The
      // endgame-boosted net is ~+20 Elo in endgames at α0.5; tapering cashes
      // that into +19.7 Elo overall (1500g A/B vs the pre-taper deployment).
      // Set == hybridAlpha to disable the taper.
      hybridAlphaEndgame: Double = 0.5,
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
                   hybridAlphaEndgame,
                 )
      base     = Search.alphaBeta(eval, book, maxTtEntries)
      search   = tablebaseOracle match
                   case Some(tb) => new TbAugmentedSearch(base, tb, tablebasePieceLimit)
                   case None     => base
    yield EngineBundle(weights, book, search, eval)

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
      hybridAlphaEndgame: Double = 0.5,
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
          .map(nnue => new HybridEvaluator(hce, nnue, hybridAlpha, hybridAlphaEndgame))
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
      hybridAlpha: Double = 0.3,
      hybridAlphaEndgame: Double = 0.5,
  ): UIO[(EngineBundle, Option[Throwable])] =
    fromResources(
      weightsVersion, maxBookPly, maxTtEntries,
      evalSource, evalCacheEnabled, evalCacheEntries, ensembleSize,
      endgameHeuristicsEnabled = endgameHeuristicsEnabled,
      hybridAlpha = hybridAlpha,
      hybridAlphaEndgame = hybridAlphaEndgame,
    )
      .map(b => (b, None))
      .catchAll { err =>
        // Same array-backed tapered eval as the success path — the fallback
        // bundle still benefits from tapered weights if a live WeightsRepo
        // later attaches a tuned snapshot with `_mg` / `_eg` keys.
        val fbEval = wrapEval(
          ArrayTaperedEvaluator(fallbackSnapshot.weights),
          evalSource, evalCacheEnabled, evalCacheEntries,
          ensembleSize, endgameHeuristicsEnabled, hybridAlpha,
        )
        ZIO.succeed(
          (
            EngineBundle(
              weights     = fallbackSnapshot,
              openingBook = OpeningBook.Empty,
              search      = Search.alphaBeta(fbEval, OpeningBook.Empty, maxTtEntries),
              eval        = fbEval,
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

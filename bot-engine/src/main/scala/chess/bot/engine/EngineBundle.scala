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

  /** Which evaluator to use under the search. The HCE path stays the
    * historical default so existing call sites + benches see no
    * behaviour change. NNUE variants opt in. */
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
      evalSource: EvalSource = EvalSource.Hce,
      evalCacheEnabled: Boolean = false,
      evalCacheEntries: Int = 1_000_000,
      ensembleSize: Int = 3,
      tablebaseOracle: Option[Search] = None,
      tablebasePieceLimit: Int = 5,
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
      eval     = wrapEval(hce, evalSource, evalCacheEnabled, evalCacheEntries, ensembleSize)
      base     = Search.alphaBeta(eval, book, maxTtEntries)
      search   = tablebaseOracle match
                   case Some(tb) => new TbAugmentedSearch(base, tb, tablebasePieceLimit)
                   case None     => base
    yield EngineBundle(weights, book, search)

  /** Pick the requested evaluator with NNUE fallbacks, then optionally
    * wrap in a Zobrist-keyed eval cache. */
  private def wrapEval(
      hce: Evaluator,
      source: EvalSource,
      cacheEnabled: Boolean,
      cacheEntries: Int,
      ensembleSize: Int,
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
    if cacheEnabled then CachedEvaluator.of(chosen, cacheEntries) else chosen

  /** Same as [[fromResources]] but on any failure, falls back to the
    * material-only evaluator + empty opening book. Returns the bundle
    * along with the failure (if any) so the caller can log it. */
  def fromResourcesOrFallback(
      weightsVersion: Int = 1,
      maxBookPly: Int = 24,
      maxTtEntries: Int = 1_000_000,
      evalSource: EvalSource = EvalSource.Hce,
      evalCacheEnabled: Boolean = false,
      evalCacheEntries: Int = 1_000_000,
      ensembleSize: Int = 3,
  ): UIO[(EngineBundle, Option[Throwable])] =
    fromResources(
      weightsVersion, maxBookPly, maxTtEntries,
      evalSource, evalCacheEnabled, evalCacheEntries, ensembleSize,
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
                  evalSource, evalCacheEnabled, evalCacheEntries, ensembleSize,
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

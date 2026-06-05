package chess.bot.engine

import zio.*

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

  /** Default bundle: load `weights/v1.json` + `openings/main-lines.pgn`
    * from the classpath, assemble [[Search]] over those. Fails fast
    * if either resource is missing or malformed — bot startup
    * shouldn't silently continue with a half-loaded engine. */
  def fromResources(
      weightsVersion: Int = 1,
      maxBookPly: Int = 24,
      maxTtEntries: Int = 1_000_000,
  ): IO[Throwable, EngineBundle] =
    for
      weights <- WeightsLoader.load(weightsVersion)
      book    <- OpeningBookLoader.loadDefault(maxBookPly)
      // Tapered runtime evaluator: looks up `_mg` / `_eg` weights and
      // blends by game phase, falling back to an unsuffixed key when
      // a tapered variant isn't present. So legacy `v1.json` /
      // `v2.json` snapshots (no `_mg` / `_eg` keys) keep working
      // exactly as they did before tapered eval landed — both
      // branches read the same unsuffixed weight and the blend
      // collapses to `weight * count`.
      eval     = TaperedEvaluator(weights.weights, FeatureExtractor.full)
      search   = Search.alphaBeta(eval, book, maxTtEntries)
    yield EngineBundle(weights, book, search)

  /** Same as [[fromResources]] but on any failure, falls back to the
    * material-only evaluator + empty opening book. Returns the bundle
    * along with the failure (if any) so the caller can log it. */
  def fromResourcesOrFallback(
      weightsVersion: Int = 1,
      maxBookPly: Int = 24,
      maxTtEntries: Int = 1_000_000,
  ): UIO[(EngineBundle, Option[Throwable])] =
    fromResources(weightsVersion, maxBookPly, maxTtEntries)
      .map(b => (b, None))
      .catchAll { err =>
        ZIO.succeed(
          (
            EngineBundle(
              weights     = fallbackSnapshot,
              openingBook = OpeningBook.Empty,
              // Same extractor + tapered eval as the success path so
              // the fallback bundle still benefits from tapered
              // weights if a live WeightsRepo later attaches a tuned
              // snapshot with `_mg` / `_eg` keys.
              search      = Search.alphaBeta(
                TaperedEvaluator(fallbackSnapshot.weights, FeatureExtractor.full),
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

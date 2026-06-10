package chess.bot.lichess

import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*

import chess.bot.engine.{EngineBundle, ParallelismBudget, Search, TbAugmentedSearch}

/** Runnable entrypoint for the Lichess bot.
  *
  * Loads the strongest engine configuration and plays every accepted
  * game with time-budgeted iterative deepening:
  *
  *   - Eval: the HCE+NNUE **Hybrid** (the strongest evaluator;
  *     `EvalSource.Hybrid` is `EngineBundle`'s default) over the **v8**
  *     tuned weights backbone (the champion HCE snapshot, +14 Elo over
  *     v4) blended with the Stockfish-distilled NNUE.
  *   - Search: [[Search.bestMoveWithBudget]] — iterative deepening to a
  *     per-move wall-clock budget. Stronger than a fixed depth (it goes
  *     deeper in quiet positions) and safe on the clock (it never blows
  *     the budget), so the bot won't flag.
  *
  * Config via env:
  *   - `LICHESS_BOT_TOKEN`      (required) personal API token of the bot account
  *   - `LICHESS_BOT_USERNAME`   (default `pichess-htwg`) used to detect our colour
  *   - `LICHESS_WEIGHTS_VERSION`(default `8`) HCE weights snapshot to load
  *   - `LICHESS_MOVE_BUDGET_MS` (default `2000`) per-move search budget in ms
  *   - `LICHESS_SEARCH_DEPTH`   (default `6`) fallback fixed depth if the
  *                              budgeted search can't complete even one iteration
  */
object LichessBotMain extends ZIOAppDefault:

  private val DefaultUsername      = "pichess-htwg"
  private val DefaultWeights       = 8
  private val DefaultFallbackDepth = 6
  private val MaxTtEntries         = 1_000_000
  private val TablebasePieceLimit  = 7 // Lichess Syzygy covers up to 7 pieces

  override def run: ZIO[Any, Throwable, Unit] =
    for
      token <- ZIO
                 .fromOption(sys.env.get("LICHESS_BOT_TOKEN").filter(_.nonEmpty))
                 .orElseFail(new RuntimeException("LICHESS_BOT_TOKEN env var is not set"))
      username      = sys.env.getOrElse("LICHESS_BOT_USERNAME", DefaultUsername)
      weightsVersion = sys.env.get("LICHESS_WEIGHTS_VERSION").flatMap(_.toIntOption).getOrElse(DefaultWeights)
      fallbackDepth  = sys.env.get("LICHESS_SEARCH_DEPTH").flatMap(_.toIntOption).getOrElse(DefaultFallbackDepth)
      lazySmp        = !sys.env.get("LICHESS_LAZYSMP").exists(_.equalsIgnoreCase("false"))
      tablebase      = !sys.env.get("LICHESS_TABLEBASE").exists(_.equalsIgnoreCase("false"))
      bundle  <- EngineBundle.fromResources(weightsVersion = weightsVersion)
      // One global LazySMP helper-thread budget, shared by every game. Each
      // game gets a FRESH isolated search (own TT + heuristic tables), reusing
      // the one loaded net — so concurrent games never share mutable search
      // state, and LazySMP fills only spare cores.
      budget         = if lazySmp then ParallelismBudget.ofCores() else ParallelismBudget.Single
      _       <- ZIO.logInfo(
                   s"Engine ready: Hybrid (HCE v${bundle.weights.version} + NNUE), " +
                     s"clock-aware time management (fallback depth $fallbackDepth), " +
                     s"LazySMP=${if lazySmp then s"on (≤${budget.permits} spare cores)" else "off"}, " +
                     s"tablebase=${if tablebase then "on (Lichess 7-piece)" else "off"}, per-game isolated. " +
                     s"Connecting to Lichess as '$username'.",
                 )
      _       <- ZIO.scoped {
                   HttpClientZioBackend.scoped().flatMap { backend =>
                     val api = BotApiClient.sttp(backend, BotApiClient.Config(token))
                     // Tablebase oracle (Lichess 7-piece API) on the shared
                     // backend; wraps each per-game search so ≤7-piece endgames
                     // get a TB-perfect move (fail-safe back to the search). The
                     // Bridge sizes each move from the clock (TimeManager), so
                     // there's no fixed-budget wrapper here.
                     val tbOracle = Option.when(tablebase)(new LichessTablebaseSearch(backend))
                     val searchFactory = () =>
                       val base = Search.alphaBeta(
                         bundle.eval, bundle.openingBook, MaxTtEntries,
                         lazySmpEnabled = lazySmp, budget = budget,
                       )
                       tbOracle.fold(base)(o => new TbAugmentedSearch(base, o, TablebasePieceLimit))
                     Bridge
                       .run(username, searchFactory, fallbackDepth, api)
                       .tapError(e =>
                         ZIO.logError(s"Lichess event stream failed, reconnecting in 5s: ${e.getMessage}"),
                       )
                       .retry(Schedule.fixed(5.seconds))
                   }
                 }
    yield ()

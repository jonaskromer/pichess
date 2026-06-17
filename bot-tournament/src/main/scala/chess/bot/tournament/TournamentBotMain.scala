package chess.bot.tournament

import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.*

import chess.bot.engine.{EngineBundle, ParallelismBudget, Search}

/** Runnable entrypoint for the NowChess tournament bot.
  *
  * Loads the strongest engine configuration (HCE+NNUE Hybrid over the champion
  * v8 weights, iterative-deepening budgeted search, LazySMP across spare cores)
  * — the same engine the Lichess bot uses — and plays every game in a
  * tournament. Move selection is identical to `LichessBotMain`; only the
  * protocol differs (see [[TournamentBridge]]).
  *
  * Config via env:
  *   - `TOURNAMENT_BASE_URL` (default production NowChess server)
  *   - `TOURNAMENT_ID` the tournament to join; if unset, auto-picks the first
  *     `created` (joinable) tournament from the list
  *   - `TOURNAMENT_BOT_NAME` (default `pichess`) our registration name
  *   - `TOURNAMENT_WEIGHTS_VERSION` (default `8`) HCE weights snapshot
  *   - `TOURNAMENT_MOVE_DEPTH` (default `6`) fallback fixed depth
  *   - `TOURNAMENT_LAZYSMP` (default on) set `false` to disable LazySMP
  *
  * Note: unlike the Lichess bot there is no tablebase oracle wired here — the
  * Lichess 7-piece tablebase is an external HTTP API we don't assume is
  * reachable from a tournament host. The NNUE-backed search is plenty strong; a
  * local Syzygy oracle can be added later if a tournament warrants it.
  */
object TournamentBotMain extends ZIOAppDefault:

  private val DefaultName = "pichess"
  private val DefaultBaseUrl = "https://nowchess.janis-eccarius.de"
  private val DefaultWeights = 8
  private val DefaultFallbackDepth = 6
  private val MaxTtEntries = 1_000_000

  override def run: ZIO[Any, Throwable, Unit] =
    for
      baseUrlStr <- ZIO.succeed(
        sys.env.getOrElse("TOURNAMENT_BASE_URL", DefaultBaseUrl)
      )
      baseUrl <- ZIO
        .fromEither(Uri.parse(baseUrlStr))
        .mapError(msg =>
          new RuntimeException(s"Invalid TOURNAMENT_BASE_URL: $msg")
        )
      botName = sys.env.getOrElse("TOURNAMENT_BOT_NAME", DefaultName)
      weightsVersion = sys.env
        .get("TOURNAMENT_WEIGHTS_VERSION")
        .flatMap(_.toIntOption)
        .getOrElse(DefaultWeights)
      fallbackDepth = sys.env
        .get("TOURNAMENT_MOVE_DEPTH")
        .flatMap(_.toIntOption)
        .getOrElse(DefaultFallbackDepth)
      lazySmp = !sys.env
        .get("TOURNAMENT_LAZYSMP")
        .exists(_.equalsIgnoreCase("false"))
      bundle <- EngineBundle.fromResources(weightsVersion = weightsVersion)
      budget =
        if lazySmp then ParallelismBudget.ofCores()
        else ParallelismBudget.Single
      _ <- ZIO.logInfo(
        s"Engine ready: Hybrid (HCE v${bundle.weights.version} + NNUE), " +
          s"clock-aware time management (fallback depth $fallbackDepth), " +
          s"LazySMP=${
              if lazySmp then s"on (≤${budget.permits} spare cores)" else "off"
            }, " +
          s"per-game isolated. Connecting to NowChess at $baseUrl as '$botName'."
      )
      _ <- ZIO.scoped {
        HttpClientZioBackend.scoped().flatMap { backend =>
          for
            api <- TournamentApiClient
              .sttp(backend, TournamentApiClient.Config(baseUrl))
            tournamentId <- resolveTournamentId(api)
            searchFactory = () =>
              Search.alphaBeta(
                bundle.eval,
                bundle.openingBook,
                MaxTtEntries,
                lazySmpEnabled = lazySmp,
                budget = budget,
                timeManagementUpgradeEnabled = true
              )
            result <- TournamentBridge
              .run(tournamentId, botName, fallbackDepth, searchFactory, api)
              .tapError(e =>
                ZIO.logError(
                  s"Tournament stream failed, reconnecting in 5s: ${e.getMessage}"
                )
              )
              .retry(Schedule.fixed(5.seconds))
          yield result
        }
      }
    yield ()

  /** Use `TOURNAMENT_ID` if set, else auto-pick the first joinable tournament.
    */
  private def resolveTournamentId(
      api: TournamentApiClient
  ): IO[Throwable, String] =
    sys.env.get("TOURNAMENT_ID").filter(_.nonEmpty) match
      case Some(id) => ZIO.succeed(id)
      case None =>
        api.listTournaments.flatMap {
          case head :: _ =>
            ZIO.logInfo(s"Auto-picked open tournament ${head.id}").as(head.id)
          case Nil =>
            ZIO.fail(
              new RuntimeException(
                "No open tournaments and TOURNAMENT_ID unset"
              )
            )
        }

package chess.bot.tournament

import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.*
import zio.http.Server
import zio.json.*

import chess.bot.engine.{EngineBundle, ParallelismBudget, Search}
import chess.obs.{MetricsHttpServer, MetricsLayer}
import chess.repository.api.{ArchiveSubmissionDto, TournamentArchiveDto}

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
  private val DefaultControlPort = 8080
  private val DefaultMetricsPort = 9107

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
            searchFactory = () =>
              Search.alphaBeta(
                bundle.eval,
                bundle.openingBook,
                MaxTtEntries,
                lazySmpEnabled = lazySmp,
                budget = budget,
                timeManagementUpgradeEnabled = true
              )
            // When PICHESS_ARCHIVE_URL is set (e.g. http://repository:8091),
            // finished games are POSTed to the repository's archive store so
            // they're analyzable/replayable like piChess's own games. Off by
            // default (no URL → no recorder).
            recorder = sys.env
              .get("PICHESS_ARCHIVE_URL")
              .map(_.trim)
              .filter(_.nonEmpty)
              .map(url =>
                GameRecorder(
                  botName,
                  archiveSink(backend, url),
                  tournamentArchiveSink(backend, url)
                )
              )
            manager <- TournamentManager.make(
              botName,
              fallbackDepth,
              searchFactory,
              api,
              recorder = recorder,
              // Self-describe in the bot registry so analytics-export attributes
              // our games with engine family / type / model version.
              metadata = Some(BotMetadata.pichess(weightsVersion))
            )
            // Prometheus metrics (tournament play + JVM) on a dedicated port,
            // scraped into the `piChess — tournament` Grafana dashboard. Forked
            // so it lives alongside the control API without blocking it.
            _           <- MetricsLayer.jvmMetricsBootstrap
            metricsPort <- MetricsHttpServer.portFromEnv(DefaultMetricsPort)
            _           <- ZIO.logInfo(
              s"Tournament metrics on :$metricsPort/metrics"
            )
            _           <- MetricsHttpServer.serve(metricsPort).forkDaemon
            controlPort = sys.env
              .get("TOURNAMENT_CONTROL_PORT")
              .flatMap(_.toIntOption)
              .getOrElse(DefaultControlPort)
            // Seed an initial join from the env (TOURNAMENT_ID / TOURNAMENT_NAME)
            // in the background, so a NAME-wait doesn't delay the control server.
            // The gateway then drives further joins via the control API.
            _ <- seedJoin(manager, api)
              .tapError(e =>
                ZIO.logError(
                  s"Seed join failed, retrying in 5s: ${e.getMessage}"
                )
              )
              .retry(Schedule.fixed(5.seconds))
              .forkDaemon
            _ <- ZIO.logInfo(
              s"Tournament control API on :$controlPort (POST/DELETE/GET /control/tournaments)."
            )
            // Serve the control API; this keeps the process alive while the
            // per-tournament daemon fibers play in the background.
            _ <- Server
              .serve(TournamentControlApi.routes(manager))
              .provide(Server.defaultWithPort(controlPort))
          yield ()
        }
      }
    yield ()

  /** POST a finished game to the repository's archive store. Best-effort: any
    * failure is logged and swallowed so archiving never disturbs play. */
  private def archiveSink(
      backend: SttpBackend[Task, ZioStreams],
      baseUrl: String
  ): ArchiveSubmissionDto => UIO[Unit] =
    dto =>
      val request = basicRequest
        .post(uri"$baseUrl/archives")
        .header("Content-Type", "application/json")
        .body(dto.toJson)
        .response(asStringAlways)
      backend
        .send(request)
        .unit
        .catchAll(e =>
          ZIO.logWarning(
            s"Failed to archive tournament game ${dto.gameId}: ${e.getMessage}"
          )
        )

  /** POST a finished tournament's record (ladder + game ids) to the repository.
    * Best-effort — failures are logged and swallowed. */
  private def tournamentArchiveSink(
      backend: SttpBackend[Task, ZioStreams],
      baseUrl: String
  ): TournamentArchiveDto => UIO[Unit] =
    dto =>
      val request = basicRequest
        .post(uri"$baseUrl/tournament-archives")
        .header("Content-Type", "application/json")
        .body(dto.toJson)
        .response(asStringAlways)
      backend
        .send(request)
        .unit
        .catchAll(e =>
          ZIO.logWarning(
            s"Failed to archive tournament ${dto.tournamentId}: ${e.getMessage}"
          )
        )

  /** Resolve an optional initial tournament to join from the env and join it:
    *   - `TOURNAMENT_ID` → join that id;
    *   - else `TOURNAMENT_NAME` → wait for a matching open tournament, then
    *     join;
    *   - else nothing (the control API drives all joins).
    */
  private def seedJoin(
      manager: TournamentManager,
      api: TournamentApiClient
  ): IO[Throwable, Unit] =
    sys.env.get("TOURNAMENT_ID").filter(_.nonEmpty) match
      case Some(id) => manager.join(id)
      case None =>
        sys.env.get("TOURNAMENT_NAME").map(_.trim).filter(_.nonEmpty) match
          case Some(pattern) => awaitNamed(api, pattern).flatMap(manager.join)
          case None =>
            ZIO.logInfo(
              "No TOURNAMENT_ID/NAME set; awaiting joins via the control API."
            )

  /** Poll the open (`created`) list until a tournament whose name contains
    * `pattern` (case-insensitive) appears; retry every 10s.
    */
  private def awaitNamed(
      api: TournamentApiClient,
      pattern: String
  ): IO[Throwable, String] =
    val want = pattern.toLowerCase
    def attempt: IO[Throwable, String] =
      api.listTournaments.flatMap { ts =>
        ts.find(_.fullName.toLowerCase.contains(want)) match
          case Some(t) =>
            ZIO
              .logInfo(
                s"Found open tournament '${t.fullName}' (${t.id}) matching '$pattern'"
              )
              .as(t.id)
          case None =>
            ZIO.logInfo(
              s"No open tournament matching '$pattern' yet; retrying in 10s…"
            ) *>
              ZIO.sleep(10.seconds) *> attempt
      }
    attempt

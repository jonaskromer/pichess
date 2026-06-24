package chess.controller

import zio.*
import zio.http.*

/** Gateway bridge for the tournament UI — keeps the browser same-origin (no
  * CORS, no tokens client-side) and the bot's control API cluster-internal.
  *
  *   - `GET /tournament/list` → relays the **public** NowChess list (`GET
  *     {tournament}/api/tournament`); the browser polls this.
  *   - `POST /tournament/{id}/join` → signals the bot to enter piChess (`POST
  *     {botControl}/control/tournaments/{id}`).
  *   - `DELETE /tournament/{id}/join` → signals the bot to withdraw.
  *
  * Spectating (NDJSON → game-service mirror → SSE) lives in
  * [[TournamentSpectate]]. The bot control API can enter piChess into arbitrary
  * tournaments, so only the gateway should reach it (ClusterIP-only Service).
  */
object TournamentProxy:

  /** Base URL of the NowChess tournament server. */
  val EnvTournamentUrl: String = "PICHESS_TOURNAMENT_URL"

  /** Base URL of the containerised bot's control API. */
  val EnvBotControlUrl: String = "PICHESS_BOT_CONTROL_URL"

  /** The name piChess registers under at the tournament server. Used to keep
    * only our own games in the default-scoped Spectate list; must match the
    * bot's `TOURNAMENT_BOT_NAME` (same default).
    */
  val EnvBotName: String = "PICHESS_BOT_NAME"

  private val DefaultTournamentUrl = "http://141.37.123.132:8086"
  private val DefaultBotControlUrl = "http://bot-tournament:8080"
  private val DefaultBotName       = "pichess"

  /** Compose `base + path` and parse it. Pulled out so the parse-failure arm (a
    * misconfigured base URL) is unit-testable without a live upstream.
    */
  private[controller] def buildUrl(
      base: String,
      path: String
  ): Either[String, URL] =
    val s = s"${base.stripSuffix("/")}$path"
    URL.decode(s).left.map(_ => s)

  def routes(
      tournamentBaseUrl: String,
      botControlUrl: String
  ): Routes[Client, Response] =
    Routes(
      Method.GET / "tournament" / "list" -> handler { (_: Request) =>
        forward(Method.GET, tournamentBaseUrl, "/api/tournament")
      },
      Method.POST / "tournament" / string("id") / "join" -> handler {
        (id: String, _: Request) =>
          forward(Method.POST, botControlUrl, s"/control/tournaments/$id")
      },
      Method.DELETE / "tournament" / string("id") / "join" -> handler {
        (id: String, _: Request) =>
          forward(Method.DELETE, botControlUrl, s"/control/tournaments/$id")
      }
    )

  /** Issue `method base+path` and return the upstream response verbatim. A bad
    * URL or an unreachable upstream becomes a 502 so a route never crashes.
    */
  private def forward(
      method: Method,
      base: String,
      path: String
  ): ZIO[Client, Nothing, Response] =
    buildUrl(base, path) match
      case Left(bad) =>
        ZIO.succeed(
          Response
            .text(s"tournament proxy: invalid URL $bad")
            .status(Status.BadGateway)
        )
      case Right(url) =>
        Client
          .batched(Request(method = method, url = url))
          .orElseSucceed(
            Response
              .text("tournament proxy: upstream unreachable")
              .status(Status.BadGateway)
          )

  def tournamentUrlFromEnv: UIO[String] =
    envOr(EnvTournamentUrl, DefaultTournamentUrl)

  def botControlUrlFromEnv: UIO[String] =
    envOr(EnvBotControlUrl, DefaultBotControlUrl)

  def botNameFromEnv: UIO[String] =
    envOr(EnvBotName, DefaultBotName)

  private def envOr(name: String, default: String): UIO[String] =
    zio.System
      .env(name)
      .map(_.filter(_.trim.nonEmpty).getOrElse(default))
      .orElseSucceed(default)

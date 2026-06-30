package chess.controller

import zio.*
import zio.http.*

/** Gateway browse of archived (finished) tournaments — relays the repository's
  * post-tournament history so the browser stays same-origin and the repository
  * stays cluster-internal.
  *
  *   - `GET /tournament/history` → repository `GET /tournament-archives` (the
  *     history index: id, name, format, finishedAt, nbPlayers, winner).
  *   - `GET /tournament/archive/{id}` → repository `GET /tournament-archives/{id}`
  *     (the ladder + game ids; each game's moves are in the per-game archive).
  *
  * A per-request timeout caps a slow/unreachable repository (mirrors
  * [[TournamentProxy]]'s relay), so a route never hangs or crashes.
  */
object TournamentHistory:

  /** Base URL of the repository service (cluster-internal). */
  val EnvRepositoryUrl: String = "PICHESS_REPOSITORY_URL"

  private val DefaultRepositoryUrl = "http://repository:8091"

  def routes(repositoryBaseUrl: String): Routes[Client, Response] =
    Routes(
      Method.GET / "tournament" / "history" -> handler { (_: Request) =>
        forward(repositoryBaseUrl, "/tournament-archives")
      },
      Method.GET / "tournament" / "archive" / string("id") -> handler {
        (id: String, _: Request) =>
          forward(repositoryBaseUrl, s"/tournament-archives/$id")
      },
      // One archived game's PGN + metadata, so the browser can open it in the
      // board view for replay/analysis. Relays the per-game archive store.
      Method.GET / "tournament" / "game" / string("gameId") -> handler {
        (gameId: String, _: Request) =>
          forward(repositoryBaseUrl, s"/archives/$gameId")
      }
    )

  /** GET base+path from the repository, relayed verbatim. A bad URL or an
    * unreachable/slow repository becomes a 502/504 rather than crashing. */
  private def forward(
      base: String,
      path: String
  ): ZIO[Client, Nothing, Response] =
    TournamentProxy.buildUrl(base, path) match
      case Left(bad) =>
        ZIO.succeed(
          Response
            .text(s"tournament history: invalid URL $bad")
            .status(Status.BadGateway)
        )
      case Right(url) =>
        Client
          .batched(Request.get(url))
          .disconnect
          .timeoutTo(
            Response
              .text("tournament history: repository timed out")
              .status(Status.GatewayTimeout)
          )(identity)(2.seconds)
          .orElseSucceed(
            Response
              .text("tournament history: repository unreachable")
              .status(Status.BadGateway)
          )

  def repositoryUrlFromEnv: UIO[String] =
    zio.System
      .env(EnvRepositoryUrl)
      .map(_.filter(_.trim.nonEmpty).getOrElse(DefaultRepositoryUrl))
      .orElseSucceed(DefaultRepositoryUrl)

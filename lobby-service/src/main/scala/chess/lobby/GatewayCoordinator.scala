package chess.lobby

import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter
import zio.*

import chess.api.{Endpoints, ErrorDto, RegisterPlayersRequest}

/** Internal coordination client between the lobby-service and the gateway.
  *
  * When a hosted lobby starts a game, the gateway needs to know the
  * lobby's host + guest sessions so its `SessionRegistry` can let both
  * players move (and reject everyone else). That hand-off happens via
  * `POST /internal/games/{id}/players` — defined in `chess.api.Endpoints`
  * and excluded from the public Swagger surface. This class is the typed
  * client wrapper for that single call.
  *
  * Reads the gateway URL from `PICHESS_GATEWAY_URL` (default
  * `http://gateway:8090` for docker-compose); the same env var the TUI
  * uses, set on every service in the compose file.
  */
trait GatewayCoordinator:
  def registerPlayers(
      gameId: String,
      hostSessionId: String,
      guestSessionId: Option[String]
  ): IO[Throwable, Unit]

object GatewayCoordinator:

  val EnvGatewayUrl: String = "PICHESS_GATEWAY_URL"

  def registerPlayers(
      gameId: String,
      hostSessionId: String,
      guestSessionId: Option[String]
  ): ZIO[GatewayCoordinator, Throwable, Unit] =
    ZIO.serviceWithZIO[GatewayCoordinator](
      _.registerPlayers(gameId, hostSessionId, guestSessionId)
    )

  /** Build a coordinator from an explicit base URI + sttp backend. The
    * default `live` layer reads the URL from the environment.
    */
  def make(
      baseUri: Uri,
      backend: SttpBackend[Task, Any]
  ): GatewayCoordinator = LiveGatewayCoordinator(baseUri, backend)

  /** Extracted so the URL-validation branch is unit-testable without
    * relying on whichever specific string sttp's `Uri.parse` rejects
    * (very few strings actually trip its forgiving parser).
    */
  private[lobby] def parseGatewayUrl(raw: String): Either[Throwable, Uri] =
    Uri
      .parse(raw)
      .left
      .map(msg => IllegalArgumentException(s"Bad $EnvGatewayUrl: $msg"))

  val live: ZLayer[Any, Throwable, GatewayCoordinator] =
    ZLayer.scoped {
      for
        url <- zio.System.env(EnvGatewayUrl).map(_.getOrElse("http://gateway:8090"))
        baseUri <- ZIO.fromEither(parseGatewayUrl(url))
        backend <- HttpClientZioBackend.scoped()
      yield make(baseUri, backend)
    }

private final class LiveGatewayCoordinator(
    baseUri: Uri,
    backend: SttpBackend[Task, Any]
) extends GatewayCoordinator:

  private val client =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(Endpoints.postRegisterPlayers, Some(baseUri))

  def registerPlayers(
      gameId: String,
      hostSessionId: String,
      guestSessionId: Option[String]
  ): IO[Throwable, Unit] =
    val request =
      client.apply((gameId, RegisterPlayersRequest(hostSessionId, guestSessionId)))
    backend
      .send(request)
      .map(_.body)
      .flatMap {
        case Right(_) => ZIO.unit
        case Left(err: ErrorDto) =>
          ZIO.fail(
            new RuntimeException(
              s"Gateway rejected /internal/games/$gameId/players: ${err.error}"
            )
          )
      }

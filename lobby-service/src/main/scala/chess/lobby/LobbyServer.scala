package chess.lobby

import chess.model.{InviteCode, LobbyError}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.*

/** Tapir-backed REST surface for the lobby microservice. Endpoint shapes
  * live in [[LobbyEndpoints]]; this module wires them to the
  * [[LobbyService]] business logic.
  */
object LobbyServer:

  def routes(svc: LobbyService): Routes[Any, Response] =
    ZioHttpInterpreter().toHttp(
      List(
        LobbyEndpoints.createLobby.zServerLogic[Any] { req =>
          svc.createLobby(req.hostNickname).mapError(toHttpError)
        },
        LobbyEndpoints.joinLobby.zServerLogic[Any] { case (rawCode, req) =>
          ZIO
            .fromOption(InviteCode(rawCode))
            .orElseFail(s"Invalid invite code: $rawCode")
            .flatMap(code =>
              svc.joinLobby(code, req.guestNickname).mapError(toHttpError)
            )
        },
        LobbyEndpoints.getLobby.zServerLogic[Any] { id =>
          svc
            .getLobby(id)
            .mapError(toHttpError)
            .flatMap {
              case Some(l) => ZIO.succeed(l)
              case None    => ZIO.fail(s"Lobby not found: $id")
            }
        },
        LobbyEndpoints.startGame.zServerLogic[Any] { case (id, req) =>
          svc.startGame(id, req.gameId).mapError(toHttpError)
        },
        LobbyEndpoints.closeLobby.zServerLogic[Any] { id =>
          svc.closeLobby(id).mapError(toHttpError).unit
        },
        LobbyEndpoints.healthcheck.zServerLogic[Any](_ => ZIO.succeed("ok"))
      )
    )

  private def toHttpError(err: LobbyError): String = err.getMessage

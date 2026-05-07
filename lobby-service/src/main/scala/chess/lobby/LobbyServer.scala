package chess.lobby

import chess.lobby.LobbyJson.PublicLobbiesResponse
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
          svc
            .createLobby(
              NewLobbyInput(
                hostNickname = req.hostNickname,
                hostSessionId = req.hostSessionId,
                visibility = req.visibility,
                allowUndo = req.allowUndo,
                allowSpectate = req.allowSpectate,
                spectatorLimit = req.spectatorLimit
              )
            )
            .mapError(toHttpError)
        },
        LobbyEndpoints.joinLobby.zServerLogic[Any] { case (rawCode, req) =>
          ZIO
            .fromOption(InviteCode(rawCode))
            .orElseFail(s"Invalid invite code: $rawCode")
            .flatMap(code =>
              svc
                .joinLobby(code, req.guestNickname, req.guestSessionId)
                .mapError(toHttpError)
            )
        },
        // Order matters: more specific paths must come before
        // `GET /lobbies/{id}`. Tapir's first-match routing sees the
        // `{id}` segment as eager and would otherwise gobble
        // `/lobbies/public` and `/lobbies/by-code/...` before they
        // reach their dedicated handlers, returning a 400
        // "Lobby not found: public".
        LobbyEndpoints.listPublic.zServerLogic[Any] { _ =>
          svc
            .listPublic()
            .mapError(toHttpError)
            .map(PublicLobbiesResponse(_))
        },
        LobbyEndpoints.findByCode.zServerLogic[Any] { rawCode =>
          ZIO
            .fromOption(InviteCode(rawCode))
            .orElseFail(s"Invalid invite code: $rawCode")
            .flatMap(code =>
              svc
                .findByCode(code)
                .mapError(toHttpError)
                .flatMap {
                  case Some(l) => ZIO.succeed(l)
                  case None    => ZIO.fail(s"Lobby not found: $rawCode")
                }
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

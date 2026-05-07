package chess.lobby

import chess.lobby.LobbyJson.{
  CreateLobbyRequest,
  JoinLobbyRequest,
  StartGameRequest,
  given
}
import chess.model.{Lobby, LobbyId}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*

/** Tapir endpoint definitions, kept in their own file so the import of
  * `sttp.tapir.*` doesn't collide with `zio.http.*` in the routes module.
  *
  * Once a second consumer (gateway proxy / web-ui / admin panel) needs the
  * contract, lift this file into a shared `lobby-api` SBT module — same shape
  * as `repository-api` already does for the repository service.
  */
object LobbyEndpoints:

  private val base = endpoint.in("lobbies").errorOut(stringBody)

  val createLobby: Endpoint[Unit, CreateLobbyRequest, String, Lobby, Any] =
    base.post.in(jsonBody[CreateLobbyRequest]).out(jsonBody[Lobby])

  val joinLobby: Endpoint[Unit, (String, JoinLobbyRequest), String, Lobby, Any] =
    base.post
      .in("by-code" / path[String]("code") / "join")
      .in(jsonBody[JoinLobbyRequest])
      .out(jsonBody[Lobby])

  val getLobby: Endpoint[Unit, LobbyId, String, Lobby, Any] =
    base.get.in(path[LobbyId]("id")).out(jsonBody[Lobby])

  val startGame: Endpoint[Unit, (LobbyId, StartGameRequest), String, Lobby, Any] =
    base.post
      .in(path[LobbyId]("id") / "start")
      .in(jsonBody[StartGameRequest])
      .out(jsonBody[Lobby])

  val closeLobby: Endpoint[Unit, LobbyId, String, Unit, Any] =
    base.delete.in(path[LobbyId]("id"))

  val healthcheck: Endpoint[Unit, Unit, Unit, String, Any] =
    endpoint.get.in("healthcheck").out(stringBody)

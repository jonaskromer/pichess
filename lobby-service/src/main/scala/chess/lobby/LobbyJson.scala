package chess.lobby

import sttp.tapir.Schema
import zio.json.*

import chess.model.{InviteCode, Lobby, LobbyStatus, LobbyVisibility}

/** Wire codecs for the lobby REST API. Lives here (not in `domain`) so the
  * cross-compiled `domain` module stays free of JSON deps; once lobby state
  * needs to flow through web-ui too, codecs migrate to a shared `lobby-api`.
  */
object LobbyJson:

  given JsonCodec[LobbyStatus] = JsonCodec[String].transformOrFail(
    raw =>
      LobbyStatus.values
        .find(_.toString == raw)
        .toRight(s"Unknown lobby status: $raw"),
    _.toString
  )

  given JsonCodec[LobbyVisibility] = JsonCodec[String].transformOrFail(
    raw =>
      LobbyVisibility.values
        .find(_.toString == raw)
        .toRight(s"Unknown lobby visibility: $raw"),
    _.toString
  )

  given JsonCodec[InviteCode] = JsonCodec[String].transformOrFail(
    raw => InviteCode(raw).toRight(s"Invalid invite code: $raw"),
    _.value
  )

  // Tapir schemas: opaque aliases / enums that derivation can't see through.
  given Schema[InviteCode] = Schema.string
  given Schema[LobbyStatus] = Schema.string
  given Schema[LobbyVisibility] = Schema.string

  given JsonCodec[Lobby] = DeriveJsonCodec.gen[Lobby]

  /** Inbound request bodies. Kept separate from domain types so HTTP
    * validation errors don't propagate into the domain model.
    */
  final case class CreateLobbyRequest(
      hostNickname: String,
      hostSessionId: String,
      visibility: LobbyVisibility,
      allowUndo: Boolean,
      allowSpectate: Boolean,
      spectatorLimit: Int
  )
  object CreateLobbyRequest:
    given JsonCodec[CreateLobbyRequest] =
      DeriveJsonCodec.gen[CreateLobbyRequest]

  final case class JoinLobbyRequest(
      guestNickname: String,
      guestSessionId: String
  )
  object JoinLobbyRequest:
    given JsonCodec[JoinLobbyRequest] =
      DeriveJsonCodec.gen[JoinLobbyRequest]

  final case class StartGameRequest(gameId: String)
  object StartGameRequest:
    given JsonCodec[StartGameRequest] =
      DeriveJsonCodec.gen[StartGameRequest]

  /** Response body for `GET /lobbies/public`. List wrapper keeps the wire
    * shape forward-compatible (we can add total/page later).
    */
  final case class PublicLobbiesResponse(lobbies: List[Lobby])
  object PublicLobbiesResponse:
    given JsonCodec[PublicLobbiesResponse] =
      DeriveJsonCodec.gen[PublicLobbiesResponse]

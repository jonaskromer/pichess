package chess.tui

import zio.json.*

/** Pure wire-shape mirrors of the gateway's lobbies proxy contract.
  *
  * Lifted out of [[TuiClient]] so the JSON codecs carry statement coverage
  * on their own — the surrounding HTTP wiring lives behind a coverage
  * exclusion (it can only be exercised against a live gateway).
  *
  * The tui module deliberately doesn't depend on `lobby-service`, so these
  * are duplicated rather than imported. Adding a field to the lobby-service
  * shape that the TUI cares about means adding it here too — kept narrow
  * (only the fields the TUI actually renders) to make the drift cost
  * visible.
  */
object TuiClientCodecs:

  enum Visibility:
    case Public, Private

  /** Wire-shape mirror of `chess.lobby.CreateLobbyRequest`. */
  final case class CreateLobbyPayload(
      hostNickname: String,
      hostSessionId: String,
      visibility: String,
      allowUndo: Boolean,
      allowSpectate: Boolean,
      spectatorLimit: Int
  )
  object CreateLobbyPayload:
    given JsonCodec[CreateLobbyPayload] = DeriveJsonCodec.gen

  final case class JoinLobbyPayload(
      guestNickname: String,
      guestSessionId: String
  )
  object JoinLobbyPayload:
    given JsonCodec[JoinLobbyPayload] = DeriveJsonCodec.gen

  final case class StartGamePayload(gameId: String)
  object StartGamePayload:
    given JsonCodec[StartGamePayload] = DeriveJsonCodec.gen

  /** Wire-shape mirror of `chess.model.Lobby` — only the fields the TUI
    * actually renders.
    */
  final case class LobbyView(
      id: String,
      inviteCode: String,
      hostNickname: String,
      hostSessionId: String,
      guestNickname: Option[String],
      guestSessionId: Option[String],
      visibility: String,
      allowUndo: Boolean,
      allowSpectate: Boolean,
      spectatorLimit: Int,
      status: String,
      gameId: Option[String]
  )
  object LobbyView:
    given JsonCodec[LobbyView] = DeriveJsonCodec.gen

  final case class PublicLobbiesResponse(lobbies: List[LobbyView])
  object PublicLobbiesResponse:
    given JsonCodec[PublicLobbiesResponse] = DeriveJsonCodec.gen

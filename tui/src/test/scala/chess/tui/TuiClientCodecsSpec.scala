package chess.tui

import zio.json.*
import zio.test.*

import chess.tui.TuiClientCodecs.*

object TuiClientCodecsSpec extends ZIOSpecDefault:

  private val sampleLobby = LobbyView(
    id = "lobby-1",
    inviteCode = "ABCDEF",
    hostNickname = "alice",
    hostSessionId = "session-host",
    guestNickname = Some("bob"),
    guestSessionId = Some("session-guest"),
    visibility = "Public",
    allowUndo = true,
    allowSpectate = false,
    spectatorLimit = 4,
    status = "Waiting",
    gameId = Some("game-1")
  )

  def spec = suite("TuiClientCodecs")(
    test("Visibility enumerates Public and Private") {
      val values = Visibility.values.toList
      assertTrue(values == List(Visibility.Public, Visibility.Private))
    },
    test("CreateLobbyPayload round-trips") {
      val v = CreateLobbyPayload(
        hostNickname = "alice",
        hostSessionId = "s",
        visibility = "Public",
        allowUndo = true,
        allowSpectate = true,
        spectatorLimit = 8
      )
      assertTrue(v.toJson.fromJson[CreateLobbyPayload] == Right(v))
    },
    test("JoinLobbyPayload round-trips") {
      val v = JoinLobbyPayload(guestNickname = "bob", guestSessionId = "s")
      assertTrue(v.toJson.fromJson[JoinLobbyPayload] == Right(v))
    },
    test("StartGamePayload round-trips") {
      val v = StartGamePayload(gameId = "game-1")
      assertTrue(v.toJson.fromJson[StartGamePayload] == Right(v))
    },
    test("LobbyView round-trips") {
      assertTrue(
        sampleLobby.toJson.fromJson[LobbyView] == Right(sampleLobby)
      )
    },
    test("PublicLobbiesResponse round-trips") {
      val v = PublicLobbiesResponse(List(sampleLobby))
      assertTrue(v.toJson.fromJson[PublicLobbiesResponse] == Right(v))
    }
  )

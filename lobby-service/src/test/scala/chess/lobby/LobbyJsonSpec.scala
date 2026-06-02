package chess.lobby

import chess.lobby.LobbyJson.{*, given}
import chess.model.{InviteCode, Lobby, LobbyStatus, LobbyVisibility}
import sttp.tapir.Schema
import zio.json.*
import zio.test.*

object LobbyJsonSpec extends ZIOSpecDefault:

  private val sampleInvite: InviteCode =
    InviteCode("ABCDEF").getOrElse(throw new AssertionError("seed code"))

  private val sampleLobby = Lobby(
    id = "lobby-1",
    inviteCode = sampleInvite,
    hostNickname = "alice",
    hostSessionId = "session-host",
    guestNickname = Some("bob"),
    guestSessionId = Some("session-guest"),
    visibility = LobbyVisibility.Public,
    allowUndo = true,
    allowSpectate = false,
    spectatorLimit = 4,
    status = LobbyStatus.Waiting,
    createdAt = 1000L,
    gameId = Some("game-1")
  )

  def spec = suite("LobbyJson")(
    suite("LobbyStatus codec")(
      test("round-trips every variant") {
        val variants = LobbyStatus.values.toList
        val decoded = variants.flatMap(s => s.toJson.fromJson[LobbyStatus].toOption)
        assertTrue(decoded == variants)
      },
      test("rejects unknown status string") {
        val result = "\"NotAStatus\"".fromJson[LobbyStatus]
        assertTrue(result == Left("(Unknown lobby status: NotAStatus)"))
      }
    ),
    suite("LobbyVisibility codec")(
      test("round-trips every variant") {
        val variants = LobbyVisibility.values.toList
        val decoded =
          variants.flatMap(v => v.toJson.fromJson[LobbyVisibility].toOption)
        assertTrue(decoded == variants)
      },
      test("rejects unknown visibility string") {
        val result = "\"Hidden\"".fromJson[LobbyVisibility]
        assertTrue(result == Left("(Unknown lobby visibility: Hidden)"))
      }
    ),
    suite("InviteCode codec")(
      test("round-trips a valid code") {
        val json = sampleInvite.toJson
        assertTrue(
          json == "\"ABCDEF\"",
          json.fromJson[InviteCode] == Right(sampleInvite)
        )
      },
      test("rejects an invalid code") {
        val result = "\"bad\"".fromJson[InviteCode]
        assertTrue(result == Left("(Invalid invite code: bad)"))
      }
    ),
    test("Lobby round-trips through JSON") {
      val json = sampleLobby.toJson
      assertTrue(json.fromJson[Lobby] == Right(sampleLobby))
    },
    test("CreateLobbyRequest round-trips") {
      val req = CreateLobbyRequest(
        hostNickname = "alice",
        hostSessionId = "session-host",
        visibility = LobbyVisibility.Private,
        allowUndo = false,
        allowSpectate = true,
        spectatorLimit = 8
      )
      assertTrue(req.toJson.fromJson[CreateLobbyRequest] == Right(req))
    },
    test("JoinLobbyRequest round-trips") {
      val req = JoinLobbyRequest(guestNickname = "bob", guestSessionId = "s")
      assertTrue(req.toJson.fromJson[JoinLobbyRequest] == Right(req))
    },
    test("StartGameRequest round-trips") {
      val req = StartGameRequest(gameId = "game-1")
      assertTrue(req.toJson.fromJson[StartGameRequest] == Right(req))
    },
    test("PublicLobbiesResponse round-trips") {
      val resp = PublicLobbiesResponse(lobbies = List(sampleLobby))
      assertTrue(resp.toJson.fromJson[PublicLobbiesResponse] == Right(resp))
    },
    test("Tapir schemas are summoned (covers Schema.string givens)") {
      val invite = summon[Schema[InviteCode]]
      val status = summon[Schema[LobbyStatus]]
      val vis = summon[Schema[LobbyVisibility]]
      assertTrue(
        invite.schemaType == Schema.string[InviteCode].schemaType,
        status.schemaType == Schema.string[LobbyStatus].schemaType,
        vis.schemaType == Schema.string[LobbyVisibility].schemaType
      )
    }
  )

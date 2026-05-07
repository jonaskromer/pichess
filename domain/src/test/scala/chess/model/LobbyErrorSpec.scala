package chess.model

import zio.test.*

object LobbyErrorSpec extends ZIOSpecDefault:

  def spec = suite("LobbyError")(
    test("LobbyNotFound mentions the lobby id") {
      val err = LobbyError.LobbyNotFound("lobby-1")
      assertTrue(err.getMessage.contains("lobby-1"))
    },
    test("InviteCodeNotFound mentions the raw code") {
      val err = LobbyError.InviteCodeNotFound("ZZZZZZ")
      assertTrue(err.getMessage.contains("ZZZZZZ"))
    },
    test("InvalidInviteCode mentions the raw input") {
      val err = LobbyError.InvalidInviteCode("nope!")
      assertTrue(err.getMessage.contains("nope!"))
    },
    test("NicknameInvalid surfaces the reason verbatim") {
      val err = LobbyError.NicknameInvalid("too short")
      assertTrue(err.getMessage == "too short")
    },
    test("LobbyNotJoinable mentions the lobby id and current status") {
      val err = LobbyError.LobbyNotJoinable("lobby-1", LobbyStatus.Closed)
      assertTrue(
        err.getMessage.contains("lobby-1"),
        err.getMessage.contains("Closed")
      )
    },
    test("LobbyNotStartable mentions the lobby id and current status") {
      val err = LobbyError.LobbyNotStartable("lobby-1", LobbyStatus.Waiting)
      assertTrue(
        err.getMessage.contains("lobby-1"),
        err.getMessage.contains("Waiting")
      )
    },
    test("InfrastructureError surfaces the underlying message") {
      val err = LobbyError.InfrastructureError("connection refused")
      assertTrue(err.getMessage == "connection refused")
    }
  )

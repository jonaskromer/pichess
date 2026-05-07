package chess.model

import zio.test.*

object LobbySpec extends ZIOSpecDefault:

  private val code = InviteCode.unsafe("ABCDEF")
  private val waiting = Lobby(
    id = "lobby-1",
    inviteCode = code,
    hostNickname = "alice",
    guestNickname = None,
    status = LobbyStatus.Waiting,
    createdAt = 0L,
    gameId = None
  )

  def spec = suite("Lobby")(
    test("LobbyStatus.isTerminal is true only for Closed") {
      assertTrue(
        !LobbyStatus.Waiting.isTerminal,
        !LobbyStatus.Full.isTerminal,
        !LobbyStatus.Started.isTerminal,
        LobbyStatus.Closed.isTerminal
      )
    },
    test("join transitions Waiting → Full and records the guest nickname") {
      val joined = waiting.join("bob")
      assertTrue(
        joined.exists(_.guestNickname.contains("bob")),
        joined.exists(_.status == LobbyStatus.Full)
      )
    },
    test("join is rejected from non-Waiting states") {
      val full = waiting.copy(status = LobbyStatus.Full)
      val started = waiting.copy(status = LobbyStatus.Started)
      val closed = waiting.copy(status = LobbyStatus.Closed)
      assertTrue(
        full.join("bob").isLeft,
        started.join("bob").isLeft,
        closed.join("bob").isLeft
      )
    },
    test("start transitions Full → Started and pins the gameId") {
      val full = waiting.copy(status = LobbyStatus.Full)
      val started = full.start("game-42")
      assertTrue(
        started.exists(_.status == LobbyStatus.Started),
        started.exists(_.gameId.contains("game-42"))
      )
    },
    test("start is rejected from non-Full states") {
      assertTrue(
        waiting.start("game-1").isLeft,
        waiting.copy(status = LobbyStatus.Started).start("game-1").isLeft,
        waiting.copy(status = LobbyStatus.Closed).start("game-1").isLeft
      )
    },
    test("close moves any state to Closed") {
      assertTrue(
        waiting.close.status == LobbyStatus.Closed,
        waiting.copy(status = LobbyStatus.Full).close.status == LobbyStatus.Closed,
        waiting.copy(status = LobbyStatus.Started).close.status == LobbyStatus.Closed,
        waiting.copy(status = LobbyStatus.Closed).close.status == LobbyStatus.Closed
      )
    }
  )

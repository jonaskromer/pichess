package chess.persistence

import chess.model.{InviteCode, Lobby, LobbyStatus}
import zio.*
import zio.test.*

object InMemoryLobbyRepositorySpec extends ZIOSpecDefault:

  private val code = InviteCode.unsafe("ABCDEF")
  private val otherCode = InviteCode.unsafe("XYZQRS")
  private val baseLobby = Lobby(
    id = "lobby-1",
    inviteCode = code,
    hostNickname = "alice",
    guestNickname = None,
    status = LobbyStatus.Waiting,
    createdAt = 0L,
    gameId = None
  )

  def spec = suite("InMemoryLobbyRepository")(
    test("findById returns None for an unknown id") {
      for result <- LobbyRepository.findById("unknown")
      yield assertTrue(result.isEmpty)
    },
    test("findByInviteCode returns None for an unknown code") {
      for result <- LobbyRepository.findByInviteCode(otherCode)
      yield assertTrue(result.isEmpty)
    },
    test("create then findById returns the lobby") {
      for
        _      <- LobbyRepository.create(baseLobby)
        result <- LobbyRepository.findById(baseLobby.id)
      yield assertTrue(result.contains(baseLobby))
    },
    test("findByInviteCode locates a created lobby by its invite code") {
      for
        _      <- LobbyRepository.create(baseLobby)
        result <- LobbyRepository.findByInviteCode(code)
      yield assertTrue(result.contains(baseLobby))
    },
    test("update overwrites existing lobby state") {
      val joined = baseLobby.copy(
        guestNickname = Some("bob"),
        status = LobbyStatus.Full
      )
      for
        _      <- LobbyRepository.create(baseLobby)
        _      <- LobbyRepository.update(joined)
        result <- LobbyRepository.findById(baseLobby.id)
      yield assertTrue(result.contains(joined))
    },
    test("delete removes the lobby") {
      for
        _      <- LobbyRepository.create(baseLobby)
        _      <- LobbyRepository.delete(baseLobby.id)
        byId   <- LobbyRepository.findById(baseLobby.id)
        byCode <- LobbyRepository.findByInviteCode(code)
      yield assertTrue(byId.isEmpty, byCode.isEmpty)
    }
  ).provide(InMemoryLobbyRepository.layer)

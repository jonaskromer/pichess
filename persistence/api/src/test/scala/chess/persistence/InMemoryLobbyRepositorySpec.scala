package chess.persistence

import chess.model.{InviteCode, Lobby, LobbyStatus, LobbyVisibility}
import zio.*
import zio.test.*

object InMemoryLobbyRepositorySpec extends ZIOSpecDefault:

  private val code = InviteCode.unsafe("ABCDEF")
  private val otherCode = InviteCode.unsafe("XYZQRS")
  private val baseLobby = Lobby(
    id = "lobby-1",
    inviteCode = code,
    hostNickname = "alice",
    hostSessionId = "session-host",
    guestNickname = None,
    guestSessionId = None,
    visibility = LobbyVisibility.Public,
    allowUndo = true,
    allowSpectate = true,
    spectatorLimit = 8,
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
        guestSessionId = Some("session-guest"),
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
    },
    suite("listPublicWaiting")(
      test("returns only public + waiting lobbies, sorted by createdAt") {
        val publicWaiting1 = baseLobby.copy(id = "L1", createdAt = 100L)
        val publicWaiting2 = baseLobby.copy(
          id = "L2",
          inviteCode = otherCode,
          createdAt = 50L
        )
        val privateWaiting = baseLobby.copy(
          id = "L3",
          inviteCode = InviteCode.unsafe("PRIVCD"),
          visibility = LobbyVisibility.Private,
          createdAt = 25L
        )
        val publicFull = baseLobby.copy(
          id = "L4",
          inviteCode = InviteCode.unsafe("FULLAB"),
          status = LobbyStatus.Full,
          createdAt = 10L
        )
        for
          _      <- LobbyRepository.create(publicWaiting1)
          _      <- LobbyRepository.create(publicWaiting2)
          _      <- LobbyRepository.create(privateWaiting)
          _      <- LobbyRepository.create(publicFull)
          result <- LobbyRepository.listPublicWaiting()
        yield assertTrue(
          result.map(_.id) == List("L2", "L1") // sorted by createdAt asc
        )
      },
      test("returns an empty list when nothing matches") {
        for result <- LobbyRepository.listPublicWaiting()
        yield assertTrue(result.isEmpty)
      }
    )
  ).provide(InMemoryLobbyRepository.layer)

package chess.persistence

import zio.*
import zio.test.*

import chess.model.{InviteCode, Lobby, LobbyStatus, LobbyVisibility}

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
    suite("listPublicActive")(
      test("returns public, non-closed lobbies sorted by createdAt") {
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
        // Full is a forming/running game — now surfaced for spectating.
        val publicFull = baseLobby.copy(
          id = "L4",
          inviteCode = InviteCode.unsafe("FULLAB"),
          status = LobbyStatus.Full,
          createdAt = 10L
        )
        // Closed lobbies are tombstones — never surfaced.
        val publicClosed = baseLobby.copy(
          id = "L5",
          inviteCode = InviteCode.unsafe("CLOSED"),
          status = LobbyStatus.Closed,
          createdAt = 5L
        )
        for
          _      <- LobbyRepository.create(publicWaiting1)
          _      <- LobbyRepository.create(publicWaiting2)
          _      <- LobbyRepository.create(privateWaiting)
          _      <- LobbyRepository.create(publicFull)
          _      <- LobbyRepository.create(publicClosed)
          result <- LobbyRepository.listPublicActive()
        yield assertTrue(
          result.map(_.id) == List("L4", "L2", "L1") // createdAt asc
        )
      },
      test("returns an empty list when nothing matches") {
        for result <- LobbyRepository.listPublicActive()
        yield assertTrue(result.isEmpty)
      }
    )
  ).provide(InMemoryLobbyRepository.layer)

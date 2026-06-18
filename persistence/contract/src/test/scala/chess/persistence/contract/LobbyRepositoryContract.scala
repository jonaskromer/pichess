package chess.persistence.contract

import zio.*
import zio.test.*

import chess.model.{InviteCode, Lobby, LobbyStatus, LobbyVisibility}
import chess.persistence.LobbyRepository

abstract class LobbyRepositoryContract extends ZIOSpecDefault:

  def repoLayer: ZLayer[Any, Throwable, LobbyRepository]
  def label: String

  private val baseLobby = Lobby(
    id = "lobby-contract",
    inviteCode = InviteCode.unsafe("ABCDEF"),
    hostNickname = "alice",
    hostSessionId = "session-host",
    guestNickname = None,
    guestSessionId = None,
    visibility = LobbyVisibility.Public,
    allowUndo = true,
    allowSpectate = true,
    spectatorLimit = 8,
    status = LobbyStatus.Waiting,
    createdAt = 1700000000000L,
    gameId = None
  )

  override final def spec =
    suite(s"LobbyRepository contract — $label")(
      test("findById returns None for an unknown id") {
        for result <- LobbyRepository.findById("missing")
        yield assertTrue(result.isEmpty)
      },
      test("findByInviteCode returns None for an unknown code") {
        for result <- LobbyRepository.findByInviteCode(
                        InviteCode.unsafe("MISSNG")
                      )
        yield assertTrue(result.isEmpty)
      },
      test("create then findById returns the lobby") {
        val l = baseLobby.copy(id = "create-1", inviteCode = InviteCode.unsafe("CREATA"))
        for
          _      <- LobbyRepository.create(l)
          result <- LobbyRepository.findById(l.id)
        yield assertTrue(result.contains(l))
      },
      test("findByInviteCode locates a created lobby") {
        // The InviteCode alphabet excludes I and O (Crockford-style — fewer
        // typo collisions). Test fixtures must respect that, otherwise the
        // strict JSON decoder in the Redis backend rejects them on round-trip.
        val code = InviteCode.unsafe("XNVCDE")
        val l = baseLobby.copy(id = "create-2", inviteCode = code)
        for
          _      <- LobbyRepository.create(l)
          result <- LobbyRepository.findByInviteCode(code)
        yield assertTrue(result.contains(l))
      },
      test("update overwrites prior state and preserves invite code") {
        val code = InviteCode.unsafe("UPDATX")
        val l = baseLobby.copy(id = "update-1", inviteCode = code)
        val joined = l.copy(
          guestNickname = Some("bob"),
          guestSessionId = Some("session-guest"),
          status = LobbyStatus.Full
        )
        for
          _       <- LobbyRepository.create(l)
          _       <- LobbyRepository.update(joined)
          byId    <- LobbyRepository.findById(l.id)
          byCode  <- LobbyRepository.findByInviteCode(code)
        yield assertTrue(
          byId.contains(joined),
          byCode.contains(joined)
        )
      },
      test("delete removes by id and clears invite-code lookup") {
        val code = InviteCode.unsafe("DELETZ")
        val l = baseLobby.copy(id = "delete-1", inviteCode = code)
        for
          _      <- LobbyRepository.create(l)
          _      <- LobbyRepository.delete(l.id)
          byId   <- LobbyRepository.findById(l.id)
          byCode <- LobbyRepository.findByInviteCode(code)
        yield assertTrue(byId.isEmpty, byCode.isEmpty)
      },
      test("listPublicActive returns public lobbies that aren't closed") {
        val pub1 = baseLobby.copy(
          id = "pub-1",
          inviteCode = InviteCode.unsafe("PUBANE"),
          createdAt = 100L
        )
        val pub2 = baseLobby.copy(
          id = "pub-2",
          inviteCode = InviteCode.unsafe("PUBTWX"),
          createdAt = 200L
        )
        val privateLobby = baseLobby.copy(
          id = "priv-1",
          inviteCode = InviteCode.unsafe("PRVATE"),
          visibility = LobbyVisibility.Private
        )
        // Full + Started are forming / running public games — the browser
        // surfaces them so they can be spectated, so they're included now.
        val fullLobby = baseLobby.copy(
          id = "full-1",
          inviteCode = InviteCode.unsafe("FULLAB"),
          status = LobbyStatus.Full
        )
        val startedLobby = baseLobby.copy(
          id = "started-1",
          inviteCode = InviteCode.unsafe("STARTD"),
          status = LobbyStatus.Started
        )
        val closedLobby = baseLobby.copy(
          id = "closed-1",
          inviteCode = InviteCode.unsafe("CLOSED"),
          status = LobbyStatus.Closed
        )
        for
          _    <- LobbyRepository.create(pub1)
          _    <- LobbyRepository.create(pub2)
          _    <- LobbyRepository.create(privateLobby)
          _    <- LobbyRepository.create(fullLobby)
          _    <- LobbyRepository.create(startedLobby)
          _    <- LobbyRepository.create(closedLobby)
          rows <- LobbyRepository.listPublicActive()
          ids   = rows.map(_.id).toSet
        yield assertTrue(
          ids.contains("pub-1"),
          ids.contains("pub-2"),
          ids.contains("full-1"),
          ids.contains("started-1"),
          !ids.contains("priv-1"),
          !ids.contains("closed-1")
        )
      }
    ).provideShared(repoLayer) @@ TestAspect.withLiveClock

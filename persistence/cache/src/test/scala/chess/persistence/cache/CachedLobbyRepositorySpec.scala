package chess.persistence.cache

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId, LobbyStatus}
import chess.persistence.{InMemoryLobbyRepository, LobbyRepository}
import zio.*
import zio.test.*

object CachedLobbyRepositorySpec extends ZIOSpecDefault:

  private final class CountingLobbyRepository(
      inner: LobbyRepository,
      creates: Ref[Int],
      reads: Ref[Int],
      updates: Ref[Int],
      deletes: Ref[Int]
  ) extends LobbyRepository:
    def create(lobby: Lobby) = creates.update(_ + 1) *> inner.create(lobby)
    def findById(id: LobbyId) = reads.update(_ + 1) *> inner.findById(id)
    def findByInviteCode(code: InviteCode) =
      reads.update(_ + 1) *> inner.findByInviteCode(code)
    def update(lobby: Lobby) = updates.update(_ + 1) *> inner.update(lobby)
    def delete(id: LobbyId) = deletes.update(_ + 1) *> inner.delete(id)

  private object CountingLobbyRepository:
    def make
        : UIO[(CountingLobbyRepository, Ref[Int], Ref[Int], Ref[Int], Ref[Int])] =
      for
        c <- Ref.make(0)
        r <- Ref.make(0)
        u <- Ref.make(0)
        d <- Ref.make(0)
        ref <- Ref.make(Map.empty[LobbyId, Lobby])
        impl = InMemoryLobbyRepository(ref)
      yield (CountingLobbyRepository(impl, c, r, u, d), c, r, u, d)

  private val code = InviteCode.unsafe("ABCDEF")
  private val baseLobby = Lobby(
    id = "lobby-1",
    inviteCode = code,
    hostNickname = "alice",
    guestNickname = None,
    status = LobbyStatus.Waiting,
    createdAt = 0L,
    gameId = None
  )

  def spec = suite("CachedLobbyRepository")(
    test("create writes through to both primary and cache") {
      for
        (cache, cacheCreates, _, _, _) <- CountingLobbyRepository.make
        (primary, primaryCreates, _, _, _) <- CountingLobbyRepository.make
        decorated = CachedLobbyRepository(cache, primary)
        _ <- decorated.create(baseLobby)
        cc <- cacheCreates.get
        pc <- primaryCreates.get
      yield assertTrue(cc == 1, pc == 1)
    },
    test("findById returns from cache without hitting primary on hit") {
      for
        (cache, _, _, _, _) <- CountingLobbyRepository.make
        (primary, _, primaryReads, _, _) <- CountingLobbyRepository.make
        decorated = CachedLobbyRepository(cache, primary)
        _ <- decorated.create(baseLobby)
        primaryReadsBefore <- primaryReads.get
        result <- decorated.findById(baseLobby.id)
        primaryReadsAfter <- primaryReads.get
      yield assertTrue(
        result.contains(baseLobby),
        primaryReadsAfter == primaryReadsBefore
      )
    },
    test("findById on cache miss populates the cache via primary") {
      for
        (cache, cacheCreates, _, _, _) <- CountingLobbyRepository.make
        (primary, _, _, _, _) <- CountingLobbyRepository.make
        // Seed primary only — cache stays cold
        _ <- primary.create(baseLobby)
        decorated = CachedLobbyRepository(cache, primary)
        result <- decorated.findById(baseLobby.id)
        creates <- cacheCreates.get
      yield assertTrue(result.contains(baseLobby), creates == 1)
    },
    test("findByInviteCode on cache miss falls through to primary") {
      for
        (cache, cacheCreates, _, _, _) <- CountingLobbyRepository.make
        (primary, _, _, _, _) <- CountingLobbyRepository.make
        _ <- primary.create(baseLobby)
        decorated = CachedLobbyRepository(cache, primary)
        result <- decorated.findByInviteCode(code)
        creates <- cacheCreates.get
      yield assertTrue(result.contains(baseLobby), creates == 1)
    },
    test("findByInviteCode returns from cache without hitting primary on hit") {
      for
        (cache, _, _, _, _) <- CountingLobbyRepository.make
        (primary, _, primaryReads, _, _) <- CountingLobbyRepository.make
        decorated = CachedLobbyRepository(cache, primary)
        _ <- decorated.create(baseLobby)
        primaryReadsBefore <- primaryReads.get
        result <- decorated.findByInviteCode(code)
        primaryReadsAfter <- primaryReads.get
      yield assertTrue(
        result.contains(baseLobby),
        primaryReadsAfter == primaryReadsBefore
      )
    },
    test("findByInviteCode on miss in both is None") {
      for
        (cache, _, _, _, _) <- CountingLobbyRepository.make
        (primary, _, _, _, _) <- CountingLobbyRepository.make
        decorated = CachedLobbyRepository(cache, primary)
        result <- decorated.findByInviteCode(InviteCode.unsafe("MISSNG"))
      yield assertTrue(result.isEmpty)
    },
    test("update writes through to both") {
      for
        (cache, _, _, cacheUpdates, _) <- CountingLobbyRepository.make
        (primary, _, _, primaryUpdates, _) <- CountingLobbyRepository.make
        decorated = CachedLobbyRepository(cache, primary)
        _ <- decorated.create(baseLobby)
        joined = baseLobby.copy(
          guestNickname = Some("bob"),
          status = LobbyStatus.Full
        )
        _ <- decorated.update(joined)
        cu <- cacheUpdates.get
        pu <- primaryUpdates.get
        result <- decorated.findById(baseLobby.id)
      yield assertTrue(cu == 1, pu == 1, result.contains(joined))
    },
    test("delete invalidates both") {
      for
        (cache, _, _, _, cacheDeletes) <- CountingLobbyRepository.make
        (primary, _, _, _, primaryDeletes) <- CountingLobbyRepository.make
        decorated = CachedLobbyRepository(cache, primary)
        _ <- decorated.create(baseLobby)
        _ <- decorated.delete(baseLobby.id)
        cd <- cacheDeletes.get
        pd <- primaryDeletes.get
        afterDelete <- decorated.findById(baseLobby.id)
      yield assertTrue(cd == 1, pd == 1, afterDelete.isEmpty)
    },
    test("layer factory wires Cache + Primary into a working repository") {
      val cacheLayer =
        InMemoryLobbyRepository.layer.map(env =>
          ZEnvironment(CachedLobbyRepository.Cache(env.get[LobbyRepository]))
        )
      val primaryLayer =
        InMemoryLobbyRepository.layer.map(env =>
          ZEnvironment(CachedLobbyRepository.Primary(env.get[LobbyRepository]))
        )
      val decoratedLayer = (cacheLayer ++ primaryLayer) >>> CachedLobbyRepository.layer
      val program = for
        _      <- LobbyRepository.create(baseLobby)
        result <- LobbyRepository.findById(baseLobby.id)
      yield result
      for result <- program.provide(decoratedLayer)
      yield assertTrue(result.contains(baseLobby))
    }
  )

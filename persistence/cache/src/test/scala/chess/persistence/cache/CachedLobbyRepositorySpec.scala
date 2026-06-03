package chess.persistence.cache

import zio.*
import zio.test.*

import chess.model.{
  InviteCode,
  Lobby,
  LobbyError,
  LobbyId,
  LobbyStatus,
  LobbyVisibility
}
import chess.persistence.{InMemoryLobbyRepository, LobbyRepository}

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
    def listPublicWaiting() = reads.update(_ + 1) *> inner.listPublicWaiting()

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
    test("listPublicWaiting bypasses the cache and reads straight from primary") {
      // The public-lobby list intentionally skips the cache because
      // it's a low-frequency, always-changing aggregation — so a call
      // must NEVER increment the cache reads but MUST increment the
      // primary reads, and must return the primary's data verbatim.
      for
        (cache, _, cacheReads, _, _) <- CountingLobbyRepository.make
        (primary, _, primaryReads, _, _) <- CountingLobbyRepository.make
        decorated = CachedLobbyRepository(cache, primary)
        _ <- primary.create(baseLobby)
        cacheReadsBefore <- cacheReads.get
        primaryReadsBefore <- primaryReads.get
        result <- decorated.listPublicWaiting()
        cacheReadsAfter <- cacheReads.get
        primaryReadsAfter <- primaryReads.get
      yield assertTrue(
        result == List(baseLobby),
        cacheReadsAfter == cacheReadsBefore,
        primaryReadsAfter == primaryReadsBefore + 1
      )
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
    },
    // Exercise the cache-failure-tolerance branches added by the
    // parallel-write change.
    test("create tolerates cache failures — primary still persists") {
      val boom = LobbyError.InfrastructureError("cache down")
      for
        primaryRef <- Ref.make(Map.empty[LobbyId, Lobby])
        primary     = InMemoryLobbyRepository(primaryRef)
        decorated   = CachedLobbyRepository(FailingLobbyRepository(boom), primary)
        _          <- decorated.create(baseLobby)
        stored     <- primaryRef.get
      yield assertTrue(stored.get(baseLobby.id).contains(baseLobby))
    },
    test("update tolerates cache failures — primary still persists") {
      val boom = LobbyError.InfrastructureError("cache down")
      val updated = baseLobby.copy(guestNickname = Some("bob"))
      for
        primaryRef <- Ref.make(Map(baseLobby.id -> baseLobby))
        primary     = InMemoryLobbyRepository(primaryRef)
        decorated   = CachedLobbyRepository(FailingLobbyRepository(boom), primary)
        _          <- decorated.update(updated)
        stored     <- primaryRef.get
      yield assertTrue(stored.get(baseLobby.id).contains(updated))
    },
    test("delete tolerates cache failures — primary still deletes") {
      val boom = LobbyError.InfrastructureError("cache down")
      for
        primaryRef <- Ref.make(Map(baseLobby.id -> baseLobby))
        primary     = InMemoryLobbyRepository(primaryRef)
        decorated   = CachedLobbyRepository(FailingLobbyRepository(boom), primary)
        _          <- decorated.delete(baseLobby.id)
        stored     <- primaryRef.get
      yield assertTrue(stored.get(baseLobby.id).isEmpty)
    },
  )

  /** Test fake that fails every call with a fixed error. Used to drive
    * the cache-failure-tolerance branches (`catchAllCause` in `create` /
    * `update` / `delete`).
    */
  private final class FailingLobbyRepository(err: LobbyError) extends LobbyRepository:
    def create(lobby: Lobby): IO[LobbyError, Unit]                     = ZIO.fail(err)
    def findById(id: LobbyId): IO[LobbyError, Option[Lobby]]           = ZIO.fail(err)
    def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] = ZIO.fail(err)
    def update(lobby: Lobby): IO[LobbyError, Unit]                     = ZIO.fail(err)
    def delete(id: LobbyId): IO[LobbyError, Unit]                      = ZIO.fail(err)
    def listPublicWaiting(): IO[LobbyError, List[Lobby]]               = ZIO.fail(err)

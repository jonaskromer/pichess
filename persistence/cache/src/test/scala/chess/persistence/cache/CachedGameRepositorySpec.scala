package chess.persistence.cache

import zio.*
import zio.test.*

import chess.model.board.GameState
import chess.model.piece.Color
import chess.model.{GameError, GameId}
import chess.persistence.{GameRepository, InMemoryGameRepository}

object CachedGameRepositorySpec extends ZIOSpecDefault:

  /** Test fake that wraps an InMemoryGameRepository and counts each call.
    * Lets the test assert who paid the cost of a given operation.
    */
  private final class CountingGameRepository(
      inner: GameRepository,
      saves: Ref[Int],
      loads: Ref[Int],
      deletes: Ref[Int]
  ) extends GameRepository:
    def save(id: GameId, state: GameState): IO[GameError, Unit] =
      saves.update(_ + 1) *> inner.save(id, state)
    def load(id: GameId): IO[GameError, Option[GameState]] =
      loads.update(_ + 1) *> inner.load(id)
    def delete(id: GameId): IO[GameError, Unit] =
      deletes.update(_ + 1) *> inner.delete(id)

  private object CountingGameRepository:
    def make: UIO[(CountingGameRepository, Ref[Int], Ref[Int], Ref[Int])] =
      for
        saves   <- Ref.make(0)
        loads   <- Ref.make(0)
        deletes <- Ref.make(0)
        ref     <- Ref.make(Map.empty[GameId, GameState])
        impl     = InMemoryGameRepository(ref)
      yield (CountingGameRepository(impl, saves, loads, deletes), saves, loads, deletes)

  private val state = GameState.initial
  private val state2 = state.copy(activeColor = Color.Black)

  def spec = suite("CachedGameRepository")(
    test("save writes to both primary and cache") {
      for
        (cache, cacheSaves, _, _) <- CountingGameRepository.make
        (primary, primarySaves, _, _) <- CountingGameRepository.make
        decorated = CachedGameRepository(cache, primary)
        _ <- decorated.save("g1", state)
        cs <- cacheSaves.get
        ps <- primarySaves.get
      yield assertTrue(cs == 1, ps == 1)
    },
    test("load returns from cache without hitting primary on cache hit") {
      for
        (cache, _, cacheLoads, _) <- CountingGameRepository.make
        (primary, _, primaryLoads, _) <- CountingGameRepository.make
        decorated = CachedGameRepository(cache, primary)
        _ <- decorated.save("g1", state) // populates both
        primaryLoadsBefore <- primaryLoads.get
        result <- decorated.load("g1")
        cl <- cacheLoads.get
        pl <- primaryLoads.get
      yield assertTrue(
        result.contains(state),
        cl >= 1, // at least the read happened on cache
        pl == primaryLoadsBefore // primary was NOT consulted again
      )
    },
    test("load on cache miss falls through to primary and populates cache") {
      for
        (cache, _, _, _) <- CountingGameRepository.make
        (primary, _, primaryLoads, _) <- CountingGameRepository.make
        // Seed primary directly so cache is cold
        _ <- primary.save("g1", state)
        decorated = CachedGameRepository(cache, primary)
        result <- decorated.load("g1")
        // Second load should now hit cache
        loadsBeforeSecond <- primaryLoads.get
        _ <- decorated.load("g1")
        loadsAfterSecond <- primaryLoads.get
      yield assertTrue(
        result.contains(state),
        loadsBeforeSecond == loadsAfterSecond // no further primary load
      )
    },
    test("load on miss in both returns None and does not populate cache") {
      for
        (cache, cacheSaves, _, _) <- CountingGameRepository.make
        (primary, _, _, _) <- CountingGameRepository.make
        decorated = CachedGameRepository(cache, primary)
        result <- decorated.load("nope")
        saves <- cacheSaves.get
      yield assertTrue(result.isEmpty, saves == 0)
    },
    test("save updates cache so the next load returns the latest value") {
      for
        (cache, _, _, _) <- CountingGameRepository.make
        (primary, _, _, _) <- CountingGameRepository.make
        decorated = CachedGameRepository(cache, primary)
        _ <- decorated.save("g1", state)
        _ <- decorated.save("g1", state2)
        result <- decorated.load("g1")
      yield assertTrue(result.contains(state2))
    },
    test("delete invalidates both primary and cache") {
      for
        (cache, _, _, cacheDeletes) <- CountingGameRepository.make
        (primary, _, _, primaryDeletes) <- CountingGameRepository.make
        decorated = CachedGameRepository(cache, primary)
        _ <- decorated.save("g1", state)
        _ <- decorated.delete("g1")
        cd <- cacheDeletes.get
        pd <- primaryDeletes.get
        // Subsequent load goes to primary (cache empty), still None
        afterDelete <- decorated.load("g1")
      yield assertTrue(cd == 1, pd == 1, afterDelete.isEmpty)
    },
    test("layer factory wires Cache + Primary into a working repository") {
      val cacheLayer =
        InMemoryGameRepository.layer.map(env =>
          ZEnvironment(CachedGameRepository.Cache(env.get[GameRepository]))
        )
      val primaryLayer =
        InMemoryGameRepository.layer.map(env =>
          ZEnvironment(CachedGameRepository.Primary(env.get[GameRepository]))
        )
      val decoratedLayer = (cacheLayer ++ primaryLayer) >>> CachedGameRepository.layer
      val program = for
        _      <- GameRepository.save("g1", state)
        result <- GameRepository.load("g1")
      yield result
      for result <- program.provide(decoratedLayer)
      yield assertTrue(result.contains(state))
    },
    // Exercise the cache-failure-tolerance branches added by the
    // parallel-write change. When cache.save / cache.delete fails, the
    // primary write must still succeed and the decorator must NOT
    // propagate the cache failure — a missing cache entry self-heals
    // on the next read.
    test("save tolerates cache failures — primary write still succeeds") {
      val boom = GameError.InfrastructureError("cache down")
      for
        primaryRef <- Ref.make(Map.empty[GameId, GameState])
        primary     = InMemoryGameRepository(primaryRef)
        decorated   = CachedGameRepository(FailingGameRepository(boom), primary)
        _          <- decorated.save("g1", state)
        stored     <- primaryRef.get
      yield assertTrue(stored.get("g1").contains(state))
    },
    test("delete tolerates cache failures — primary delete still succeeds") {
      val boom = GameError.InfrastructureError("cache down")
      for
        primaryRef <- Ref.make(Map("g1" -> state))
        primary     = InMemoryGameRepository(primaryRef)
        decorated   = CachedGameRepository(FailingGameRepository(boom), primary)
        _          <- decorated.delete("g1")
        stored     <- primaryRef.get
      yield assertTrue(stored.get("g1").isEmpty)
    },
  )

  /** Test fake that fails every call with a fixed error. Used to drive
    * the cache-failure-tolerance branches (`catchAllCause` in `save` /
    * `delete`).
    */
  private final class FailingGameRepository(err: GameError) extends GameRepository:
    def save(id: GameId, state: GameState): IO[GameError, Unit] = ZIO.fail(err)
    def load(id: GameId): IO[GameError, Option[GameState]]      = ZIO.fail(err)
    def delete(id: GameId): IO[GameError, Unit]                 = ZIO.fail(err)

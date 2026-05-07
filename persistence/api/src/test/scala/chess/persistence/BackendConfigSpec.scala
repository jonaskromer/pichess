package chess.persistence

import zio.*
import zio.test.*

object BackendConfigSpec extends ZIOSpecDefault:

  def spec = suite("BackendConfig")(
    suite("parseBackend")(
      test("absent / empty / 'inmemory' all map to InMemory") {
        assertTrue(
          BackendConfig.parseBackend(None) == Right(Backend.InMemory),
          BackendConfig.parseBackend(Some("")) == Right(Backend.InMemory),
          BackendConfig.parseBackend(Some("inmemory")) == Right(Backend.InMemory)
        )
      },
      test("recognises every documented backend, case-insensitive, trimmed") {
        assertTrue(
          BackendConfig.parseBackend(Some("Postgres")) ==
            Right(Backend.Postgres),
          BackendConfig.parseBackend(Some("  MONGO ")) ==
            Right(Backend.Mongo),
          BackendConfig.parseBackend(Some("redis")) == Right(Backend.Redis),
          BackendConfig.parseBackend(Some("cassandra")) ==
            Right(Backend.Cassandra)
        )
      },
      test("unknown values fail with a descriptive error") {
        val result = BackendConfig.parseBackend(Some("typo"))
        assertTrue(
          result.isLeft,
          result.left.exists(_.getMessage.contains("PICHESS_BACKEND")),
          result.left.exists(_.getMessage.contains("typo"))
        )
      }
    ),
    suite("parseCache")(
      test("absent / empty / 'none' / 'nocache' all map to NoCache") {
        assertTrue(
          BackendConfig.parseCache(None) == Right(CacheBackend.NoCache),
          BackendConfig.parseCache(Some("")) == Right(CacheBackend.NoCache),
          BackendConfig.parseCache(Some("none")) == Right(CacheBackend.NoCache),
          BackendConfig.parseCache(Some("nocache")) ==
            Right(CacheBackend.NoCache)
        )
      },
      test("'redis' maps to CacheBackend.Redis (case-insensitive)") {
        assertTrue(
          BackendConfig.parseCache(Some("redis")) == Right(CacheBackend.Redis),
          BackendConfig.parseCache(Some("REDIS")) == Right(CacheBackend.Redis)
        )
      },
      test("unknown values fail with a descriptive error") {
        val result = BackendConfig.parseCache(Some("memcached"))
        assertTrue(
          result.isLeft,
          result.left.exists(_.getMessage.contains("PICHESS_CACHE")),
          result.left.exists(_.getMessage.contains("memcached"))
        )
      }
    ),
    suite("fromEnv")(
      test("reads PICHESS_BACKEND + PICHESS_CACHE from the env") {
        val program = BackendConfig.fromEnv
        for
          _ <- TestSystem.putEnv(BackendConfig.EnvBackend, "postgres")
          _ <- TestSystem.putEnv(BackendConfig.EnvCache, "redis")
          cfg <- program
        yield assertTrue(
          cfg == BackendConfig(Backend.Postgres, CacheBackend.Redis)
        )
      },
      test("missing env defaults both axes to in-memory + no cache") {
        for cfg <- BackendConfig.fromEnv
        yield assertTrue(
          cfg == BackendConfig(Backend.InMemory, CacheBackend.NoCache)
        )
      },
      test("a malformed PICHESS_BACKEND fails the effect") {
        for
          _    <- TestSystem.putEnv(BackendConfig.EnvBackend, "bogus")
          exit <- BackendConfig.fromEnv.exit
        yield assertTrue(exit.isFailure)
      }
    )
  )

package chess.repository

import zio.*
import zio.test.*

import chess.persistence.{
  Backend,
  BackendConfig,
  CacheBackend,
  InMemoryGameArchiveRepository,
  InMemoryGameRepository
}

object RepositoryMainSpec extends ZIOSpecDefault:

  def spec = suite("RepositoryMain")(
    suite("parsePort")(
      test("returns the parsed value when the env variable is a valid Int") {
        assertTrue(RepositoryMain.parsePort(Some("9090")) == 9090)
      },
      test("falls back to default when the env variable is not numeric") {
        assertTrue(
          RepositoryMain.parsePort(
            Some("not-a-number")
          ) == RepositoryMain.defaultPort
        )
      },
      test("falls back to default when the env variable is unset") {
        assertTrue(RepositoryMain.parsePort(None) == RepositoryMain.defaultPort)
      }
    ),
    suite("portFromEnv")(
      test("reads REPOSITORY_PORT through zio.System") {
        for
          _ <- TestSystem.putEnv("REPOSITORY_PORT", "9091")
          port <- RepositoryMain.portFromEnv
        yield assertTrue(port == 9091)
      },
      test("uses default when REPOSITORY_PORT is unset") {
        for port <- RepositoryMain.portFromEnv
        yield assertTrue(port == RepositoryMain.defaultPort)
      }
    ),
    test("serve binds the requested port and serves until interrupted") {
      // Port 0 lets the OS pick a free port — keeps the test independent
      // of any service bound to the canonical 8091.
      for
        fiber <- RepositoryMain
          .serve(0)
          .provide(
            InMemoryGameRepository.layer,
            InMemoryGameArchiveRepository.layer,
            chess.obs.TracingLayer.noop
          )
          .fork
        _ <- Live.live(ZIO.sleep(300.millis))
        _ <- fiber.interrupt
      yield assertCompletes
    },
    test("run reads the env-configured port and delegates to serve") {
      for
        _ <- TestSystem.putEnv("REPOSITORY_PORT", "0")
        fiber <- RepositoryMain.run
          .provide(ZLayer.succeed(ZIOAppArgs(Chunk.empty)))
          .fork
        _ <- Live.live(ZIO.sleep(300.millis))
        _ <- fiber.interrupt
      yield assertCompletes
    },
    suite("gameRepoLayer")(
      test("InMemory selection produces a working repository layer") {
        // gameRepoLayer is now traced — wraps the backend with
        // TracedGameRepository, so it needs a Tracing layer. Tests use
        // noop tracing since they don't assert on span output.
        val layer = RepositoryMain.gameRepoLayer(
          BackendConfig(Backend.InMemory, CacheBackend.NoCache)
        )
        for repo <- ZIO
                      .service[chess.persistence.GameRepository]
                      .provide(layer, chess.obs.TracingLayer.noop)
        yield assertTrue(repo != null)
      },
      test("backend with missing connection env fails layer construction") {
        // No PICHESS_MONGO_URL set => Mongo settings fail at startup. The
        // exact exception class depends on the backend; we just assert
        // failure (any cause), since that's the contract: a misconfigured
        // backend MUST refuse to start rather than fall back silently.
        val layer = RepositoryMain.gameRepoLayer(
          BackendConfig(Backend.Mongo, CacheBackend.NoCache)
        )
        for
          exit <- ZIO
                    .service[chess.persistence.GameRepository]
                    .provide(layer, chess.obs.TracingLayer.noop)
                    .exit
        yield assertTrue(exit.isFailure)
      }
    )
  )

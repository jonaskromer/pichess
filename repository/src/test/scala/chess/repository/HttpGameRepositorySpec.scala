package chess.repository

import chess.model.{GameError, GameId}
import chess.model.board.GameState
import chess.model.piece.Color
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.*
import zio.http.*
import zio.test.*

/** Integration test for [[HttpGameRepository]] against a real
  * [[RepositoryServer]] on an ephemeral port. Exercises the wire format,
  * status-code handling (404 → None, 5xx → InfrastructureError), and
  * transport-level failure modes.
  */
object HttpGameRepositorySpec extends ZIOSpecDefault:

  /** A repository whose every operation fails — used to drive the server's
    * 5xx error paths and the corresponding client mapping.
    */
  private object BoomRepository extends GameRepository:
    private val boom = GameError.InfrastructureError("backend boom")
    def save(id: GameId, state: GameState): IO[GameError, Unit] = ZIO.fail(boom)
    def load(id: GameId): IO[GameError, Option[GameState]]      = ZIO.fail(boom)
    def delete(id: GameId): IO[GameError, Unit]                 = ZIO.fail(boom)

  private def withClientBacked[A](repo: GameRepository)(
      run: GameRepository => ZIO[Scope, Throwable, A]
  ): ZIO[Scope, Throwable, A] =
    for
      serverEnv   <- Server.defaultWithPort(0).build
      port        <- Server
        .install(RepositoryServer.routes(repo))
        .provideEnvironment(serverEnv)
      sttpBackend <- HttpClientZioBackend.scoped()
      uri         <- ZIO
        .fromEither(Uri.parse(s"http://localhost:$port"))
        .mapError(m => new RuntimeException(m))
      client       = new HttpGameRepository(uri, sttpBackend)
      result      <- run(client)
    yield result

  private def withClient[A](
      run: GameRepository => ZIO[Scope, Throwable, A]
  ): ZIO[Scope, Throwable, A] =
    for
      store  <- Ref.make(Map.empty[GameId, GameState])
      result <- withClientBacked(new InMemoryGameRepository(store))(run)
    yield result

  private def withDeadClient[A](
      run: GameRepository => ZIO[Any, Nothing, A]
  ): ZIO[Scope, Throwable, A] =
    for
      backend <- HttpClientZioBackend.scoped()
      uri     <- ZIO
        .fromEither(Uri.parse("http://127.0.0.1:1"))
        .mapError(m => new RuntimeException(m))
      client   = new HttpGameRepository(uri, backend)
      result  <- run(client)
    yield result

  private def isInfraError(msgFragment: String): Either[GameError, Any] => Boolean =
    case Left(GameError.InfrastructureError(m)) => m.contains(msgFragment)
    case _                                      => false

  def spec = suite("HttpGameRepository integration")(
    test("round-trip save → load") {
      withClient { client =>
        val state = GameState.initial
        for
          _      <- client.save("g1", state)
          loaded <- client.load("g1")
        yield assertTrue(loaded == Some(state))
      }
    },
    test("load an unknown game returns None (404 is not an error)") {
      withClient { client =>
        client
          .load("never-saved")
          .map(r => assertTrue(r.isEmpty))
      }
    },
    test("save overwrites an existing entry") {
      withClient { client =>
        val s1 = GameState.initial
        val s2 = s1.copy(activeColor = Color.Black)
        for
          _      <- client.save("g1", s1)
          _      <- client.save("g1", s2)
          loaded <- client.load("g1")
        yield assertTrue(loaded == Some(s2))
      }
    },
    test("delete removes a saved game; later load returns None") {
      withClient { client =>
        for
          _      <- client.save("g1", GameState.initial)
          _      <- client.delete("g1")
          loaded <- client.load("g1")
        yield assertTrue(loaded.isEmpty)
      }
    },
    test("delete of unknown id is idempotent") {
      withClient { client =>
        client.delete("never-saved").as(assertCompletes)
      }
    },
    test("games are isolated by id") {
      withClient { client =>
        val s1 = GameState.initial
        val s2 = s1.copy(activeColor = Color.Black)
        for
          _   <- client.save("g1", s1)
          _   <- client.save("g2", s2)
          g1  <- client.load("g1")
          g2  <- client.load("g2")
          g3  <- client.load("g3")
        yield assertTrue(
          g1 == Some(s1),
          g2 == Some(s2),
          g3.isEmpty,
        )
      }
    },
    suite("backend failure → 500 → InfrastructureError")(
      test("save surfaces as InfrastructureError carrying the server message") {
        withClientBacked(BoomRepository) { client =>
          client
            .save("g1", GameState.initial)
            .either
            .map(e =>
              assertTrue(isInfraError("Save failed: backend boom")(e))
            )
        }
      },
      test("load surfaces as InfrastructureError") {
        withClientBacked(BoomRepository) { client =>
          client
            .load("g1")
            .either
            .map(e =>
              assertTrue(isInfraError("Load failed: backend boom")(e))
            )
        }
      },
      test("delete surfaces as InfrastructureError") {
        withClientBacked(BoomRepository) { client =>
          client
            .delete("g1")
            .either
            .map(e =>
              assertTrue(isInfraError("Delete failed: backend boom")(e))
            )
        }
      },
    ),
    suite("transport failure → InfrastructureError")(
      // Port 1 is privileged and almost never bound, so connecting fails
      // with "connection refused" — exercises the Throwable path of the
      // client (separate from server-emitted 5xx). One sub-test per
      // operation so each method's `mapError(transportError)` site is hit.
      test("load") {
        withDeadClient(_.load("g1").either)
          .map(e => assertTrue(isInfraError("repository HTTP error")(e)))
      },
      test("save") {
        withDeadClient(_.save("g1", GameState.initial).either)
          .map(e => assertTrue(isInfraError("repository HTTP error")(e)))
      },
      test("delete") {
        withDeadClient(_.delete("g1").either)
          .map(e => assertTrue(isInfraError("repository HTTP error")(e)))
      },
    ),
    suite("layer")(
      test("wires up a working client; transport failures still propagate") {
        ZIO
          .serviceWithZIO[GameRepository](_.load("g1"))
          .either
          .provide(HttpGameRepository.layer("http://127.0.0.1:1"))
          .map(e => assertTrue(isInfraError("repository HTTP error")(e)))
      },
      test("fails to build with 'Invalid base uri' when java.net.URI rejects the input") {
        // An unencoded space is rejected by java.net.URI.create — the
        // strictest URI parsing path the layer goes through.
        ZIO
          .serviceWithZIO[GameRepository](_.load("g1"))
          .provide(HttpGameRepository.layer("http://example.com/has space"))
          .either
          .map {
            case Left(t)  => assertTrue(t.getMessage.contains("Invalid base uri"))
            case Right(_) => assertTrue(false)
          }
      },
    ),
  ).provide(Scope.default)

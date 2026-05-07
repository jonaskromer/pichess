package chess.controller

import chess.events.InMemoryGameEventProducer
import chess.gameservice.{GameSessions, GrpcServer}
import chess.persistence.InMemoryGameRepository
import chess.service.GameServiceLive
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import pichess.game_service.ZioGameService
import scalapb.zio_grpc.{ServerLayer, ZManagedChannel}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

/** Smoke tests for the gateway's REST surface. The gateway is wired against
  * the gameService gRPC contract; the server side is the real `GrpcServer`
  * impl bound to an in-process gRPC transport so tests don't need a real
  * port. The client is the generated `GameServiceClient` connected via the
  * same in-process channel — exactly the production code path, just without
  * a network. Each test gets a fresh server (UUID-named) so parallel
  * execution doesn't collide on the in-process registry.
  */
object WebControllerRoutesSpec extends ZIOSpecDefault:

  private def grpcLayer(name: String) =
    ZLayer.make[ZioGameService.GameServiceClient & scalapb.zio_grpc.Server](
      InMemoryGameRepository.layer,
      GameSessions.layer,
      GameServiceLive.layer,
      InMemoryGameEventProducer.layer,
      GrpcServer.asServiceLayer,
      ServerLayer.fromEnvironment[ZioGameService.GameService](
        InProcessServerBuilder.forName(name).directExecutor()
      ),
      ZioGameService.GameServiceClient.live(
        ZManagedChannel(
          InProcessChannelBuilder.forName(name).directExecutor()
        ),
        options = io.grpc.CallOptions.DEFAULT
      )
    )

  private val testSession: String = "session-test-aaa"

  private def runWith[A](
      body: Routes[Any, Response] => ZIO[Scope, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    for
      // System.nanoTime gives a unique name across tests; ZIO test's
      // TestRandom is deterministic so Random.nextUUID would collide.
      name <- ZIO.succeed(s"pichess-test-${java.lang.System.nanoTime()}")
      out <- ZIO.scoped {
               (for
                 client   <- ZIO.service[ZioGameService.GameServiceClient]
                 registry <- chess.controller.SessionRegistry.make
                 cache    <- chess.controller.AnnotationCache.make
                 routes    = WebController.routes(client, registry, cache)
                 result   <- body(routes)
               yield result).provideSomeLayer[Scope](grpcLayer(name))
             }
    yield out

  /** Helper: attach the test session header to a request. */
  private def withSession(req: Request, session: String = testSession): Request =
    req.addHeader(Header.Custom("X-Session-Id", session))

  /** Helper: create a fresh game via POST /api/games and return its id. */
  private def createGame(routes: Routes[Any, Response]): ZIO[Scope, Throwable, String] =
    for
      response <- routes.runZIO(
                    withSession(
                      Request.post(url"/api/games", Body.fromString("""{}"""))
                    )
                  )
      body     <- response.body.asString
      // Snapshot shape: {"id": "...", "state": {...}} — pluck the id with a
      // regex to keep the test free of full DTO decoding plumbing.
      id        = """"id":"([^"]+)"""".r.findFirstMatchIn(body).get.group(1)
    yield id

  def spec = suite("WebController routes")(
    test("POST /api/games returns a new game id and initial state") {
      runWith { routes =>
        for
          response <- routes.runZIO(
                        withSession(
                          Request.post(url"/api/games", Body.fromString("""{}"""))
                        )
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"id\":\""),
          body.contains("\"activeColor\":\"white\"")
        )
      }
    },
    test("GET /api/games/{id}/state returns JSON with activeColor") {
      runWith { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(Request.get(url"/api/games/$id/state"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"activeColor\":\"white\"")
        )
      }
    },
    test("POST /api/games/{id}/move applies a legal move and returns the updated state") {
      runWith { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(
                        withSession(
                          Request.post(
                            url"/api/games/$id/move",
                            Body.fromString("""{"move":"e2 e4"}""")
                          )
                        )
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"activeColor\":\"black\""),
          body.contains("\"san\":\"e4\"")
        )
      }
    },
    test("POST /api/games/{id}/move returns 400 for an illegal move") {
      runWith { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(
                        withSession(
                          Request.post(
                            url"/api/games/$id/move",
                            Body.fromString("""{"move":"e2 e5"}""")
                          )
                        )
                      )
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("POST /api/games/{id}/move from a different session returns 400 (forbidden)") {
      runWith { routes =>
        for
          id       <- createGame(routes)  // creator session = testSession
          response <- routes.runZIO(
                        withSession(
                          Request.post(
                            url"/api/games/$id/move",
                            Body.fromString("""{"move":"e2 e4"}""")
                          ),
                          session = "session-other-bbb"
                        )
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.BadRequest,
          body.contains("Forbidden")
        )
      }
    },
    test("POST /api/games/{id}/undo with no moves returns 400") {
      runWith { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(
                        withSession(
                          Request.post(url"/api/games/$id/undo", Body.empty)
                        )
                      )
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("multiple games run side by side without interfering") {
      runWith { routes =>
        for
          g1 <- createGame(routes)
          g2 <- createGame(routes)
          // Move on g1 only.
          _  <- routes.runZIO(
                  withSession(
                    Request.post(
                      url"/api/games/$g1/move",
                      Body.fromString("""{"move":"e2 e4"}""")
                    )
                  )
                )
          s1 <- routes.runZIO(Request.get(url"/api/games/$g1/state"))
                  .flatMap(_.body.asString)
          s2 <- routes.runZIO(Request.get(url"/api/games/$g2/state"))
                  .flatMap(_.body.asString)
        yield assertTrue(
          g1 != g2,
          s1.contains("\"san\":\"e4\""),  // g1 advanced
          !s2.contains("\"san\":\"e4\""), // g2 untouched
          s2.contains("\"activeColor\":\"white\"")
        )
      }
    },
    test("GET /api/games/{id}/export/fen returns the FEN of the position") {
      runWith { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(
                        Request.get(url"/api/games/$id/export/fen")
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("rnbqkbnr/pppppppp")
        )
      }
    },
    test("POST /api/quit no longer exists") {
      runWith { routes =>
        for response <- routes.runZIO(Request.post(url"/api/quit", Body.empty))
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("GET / serves the HTML page") {
      runWith { routes =>
        for
          response <- routes.runZIO(Request.get(url"/"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          response.headers
            .get(Header.ContentType)
            .exists(_.mediaType == MediaType.text.html),
          body.contains("<html")
        )
      }
    }
  )

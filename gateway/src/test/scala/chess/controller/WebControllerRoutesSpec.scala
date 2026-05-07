package chess.controller

import chess.events.InMemoryGameEventProducer
import chess.gameservice.{GameSessions, GrpcServer}
import chess.persistence.InMemoryGameRepository
import chess.service.GameServiceLive
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import pichess.game_service.{NewGameRequest, ZioGameService}
import scalapb.zio_grpc.{ServerLayer, ZManagedChannel}
import zio.*
import zio.http.*
import zio.stream.SubscriptionRef
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

  private def runWith[A](
      body: (
          Routes[Any, Response],
          SubscriptionRef[String],
          Promise[Nothing, Unit]
      ) => ZIO[Scope, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    for
      // System.nanoTime gives a unique name across tests; ZIO test's
      // TestRandom is deterministic so Random.nextUUID would collide.
      name <- ZIO.succeed(s"pichess-test-${java.lang.System.nanoTime()}")
      out <- ZIO.scoped {
               (for
                 client       <- ZIO.service[ZioGameService.GameServiceClient]
                 initial      <- client.newGame(NewGameRequest()).orDie
                 activeGameId <- SubscriptionRef.make(initial.gameId)
                 shutdown     <- Promise.make[Nothing, Unit]
                 routes        = WebController.routes(client, activeGameId, shutdown)
                 result       <- body(routes, activeGameId, shutdown)
               yield result).provideSomeLayer[Scope](grpcLayer(name))
             }
    yield out

  def spec = suite("WebController routes")(
    test("GET /api/state returns JSON with activeColor") {
      runWith { (routes, _, _) =>
        for
          response <- routes.runZIO(Request.get(url"/api/state"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"activeColor\":\"white\"")
        )
      }
    },
    test("POST /api/move applies a legal move and returns the updated state") {
      runWith { (routes, _, _) =>
        for
          response <- routes.runZIO(
                        Request.post(
                          url"/api/move",
                          Body.fromString("""{"move":"e2 e4"}""")
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
    test("POST /api/move returns 400 for an illegal move") {
      runWith { (routes, _, _) =>
        for
          response <- routes.runZIO(
                        Request.post(
                          url"/api/move",
                          Body.fromString("""{"move":"e2 e5"}""")
                        )
                      )
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("POST /api/undo with no moves returns 400") {
      runWith { (routes, _, _) =>
        for response <- routes.runZIO(Request.post(url"/api/undo", Body.empty))
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("POST /api/new starts a fresh game and updates activeGameId") {
      runWith { (routes, activeGameId, _) =>
        for
          before   <- activeGameId.get
          _        <- routes.runZIO(
                        Request.post(
                          url"/api/move",
                          Body.fromString("""{"move":"e2 e4"}""")
                        )
                      )
          response <- routes.runZIO(Request.post(url"/api/new", Body.empty))
          after    <- activeGameId.get
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          before != after,
          body.contains("\"activeColor\":\"white\""),
          body.contains("\"moveLog\":[]")
        )
      }
    },
    test("GET /api/export/fen returns the FEN of the current position") {
      runWith { (routes, _, _) =>
        for
          response <- routes.runZIO(Request.get(url"/api/export/fen"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("rnbqkbnr/pppppppp")
        )
      }
    },
    test("GET / serves the HTML page") {
      runWith { (routes, _, _) =>
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

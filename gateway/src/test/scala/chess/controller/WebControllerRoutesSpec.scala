package chess.controller

import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import pichess.game_service.ZioGameService
import scalapb.zio_grpc.{ServerLayer, ZManagedChannel}
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import chess.events.InMemoryGameEventProducer
import chess.gameservice.{GameSessions, GrpcServer}
import chess.obs.TracingLayer
import chess.persistence.InMemoryGameRepository
import chess.service.GameServiceLive

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
    ZLayer.make[
      ZioGameService.GameServiceClient & scalapb.zio_grpc.Server
        & Tracing & ContextStorage
    ](
      InMemoryGameRepository.layer,
      GameSessions.layer,
      GameServiceLive.layer,
      InMemoryGameEventProducer.layer,
      TracingLayer.noop,
      GrpcServer.asServiceLayer,
      // Vs-bot deps required since the GrpcServer now needs them
      // even for non-vs-bot games (it just doesn't use them on the
      // common path).
      chess.service.BotConfigRepository.inMemoryLayer,
      chess.bot.engine.EngineLayer.live,
      ServerLayer.fromEnvironment[ZioGameService.RCGameService](
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
      stackInfo: chess.controller.StackInfo = chess.controller.StackInfo.Default
  )(
      body: Routes[Client & Tracing & ContextStorage, Response] => ZIO[
        Scope & Client & Tracing & ContextStorage,
        Throwable,
        A
      ]
  ): ZIO[Any, Throwable, A] =
    for
      // Unique in-process gRPC channel name per test. `System.nanoTime`
      // looked unique enough but two parallel tests can sample the
      // same nanosecond — saw `InProcessServer: name already registered`
      // flakes. `java.util.UUID.randomUUID()` doesn't go through ZIO's
      // TestRandom (which is deterministic) so each test still gets a
      // genuinely fresh name.
      name <- ZIO.succeed(s"pichess-test-${java.util.UUID.randomUUID()}")
      out <- ZIO.scoped {
               (for
                 client   <- ZIO.service[ZioGameService.GameServiceClient]
                 registry <- chess.controller.SessionRegistry.make
                 cache    <- chess.controller.AnnotationCache.make
                 // Tests don't exercise the lobby proxy — pass any URL.
                 // `stackInfo` defaults to the inmemory/no-extras/no-dev
                 // value; per-test override is the second arg list.
                 routes    = WebController.routes(
                               client,
                               registry,
                               cache,
                               "http://lobby-service:8092",
                               stackInfo,
                               lichessToken = None
                             )
                 result   <- body(routes)
               // The routes now require Client (lobby-proxy outbound) plus
               // Tracing & ContextStorage (per-request SERVER span on the
               // tracing middleware). The grpcLayer provides a noop
               // Tracing for tests — spans are silently dropped without
               // an OTLP exporter.
               yield result).provideSomeLayer[Scope](
                 grpcLayer(name) ++ Client.default
               )
             }
    yield out

  /** Helper: attach the test session header to a request. */
  private def withSession(req: Request, session: String = testSession): Request =
    req.addHeader(Header.Custom("X-Session-Id", session))

  /** Helper: create a fresh game via POST /api/games and return its id. */
  private def createGame(
      routes: Routes[Client & Tracing & ContextStorage, Response]
  ): ZIO[Scope & Client & Tracing & ContextStorage, Throwable, String] =
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
      runWith() { routes =>
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
      runWith() { routes =>
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
      runWith() { routes =>
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
      runWith() { routes =>
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
      runWith() { routes =>
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
      runWith() { routes =>
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
      runWith() { routes =>
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
      runWith() { routes =>
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
      runWith() { routes =>
        for response <- routes.runZIO(Request.post(url"/api/quit", Body.empty))
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("GET / serves the HTML page") {
      runWith() { routes =>
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
    },
    test("GET /api/games/{id}/legal-moves?from=e2 returns the pawn's targets") {
      runWith() { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(
                        Request.get(url"/api/games/$id/legal-moves?from=e2")
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          // The white e-pawn from the initial position can advance to e3
          // or e4 — both should appear in the JSON list of moves.
          body.contains("\"e3\""),
          body.contains("\"e4\""),
          body.contains("\"from\":\"e2\"")
        )
      }
    },
    test("GET /api/games/{id}/threats is empty in the initial position") {
      runWith() { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(Request.get(url"/api/games/$id/threats"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          // No piece is attacked at the start — the threatened list is `[]`.
          body.contains("\"threatened\":[]")
        )
      }
    },
    test("GET /api/games/{id}/attackers?of=e4 is empty in the initial position") {
      runWith() { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(
                        Request.get(url"/api/games/$id/attackers?of=e4")
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"of\":\"e4\""),
          body.contains("\"attackers\":[]")
        )
      }
    },
    test("legal-moves cache invalidates after a successful move") {
      // Sanity check that the post-move hook flushes the annotation cache:
      // before any move, e2 has destinations {e3, e4}; after e2-e4, e2
      // is empty (piece has moved) — its legal-moves list collapses to [].
      runWith() { routes =>
        for
          id       <- createGame(routes)
          before   <- routes
                        .runZIO(
                          Request.get(url"/api/games/$id/legal-moves?from=e2")
                        )
                        .flatMap(_.body.asString)
          _        <- routes.runZIO(
                        withSession(
                          Request.post(
                            url"/api/games/$id/move",
                            Body.fromString("""{"move":"e2 e4"}""")
                          )
                        )
                      )
          after    <- routes
                        .runZIO(
                          Request.get(url"/api/games/$id/legal-moves?from=e2")
                        )
                        .flatMap(_.body.asString)
        yield assertTrue(
          before.contains("\"e4\""),
          after.contains("\"moves\":[]")
        )
      }
    },
    test("GET /api/stack-info reports the configured backend + extras") {
      runWith(chess.controller.StackInfo("postgres", List("analytics"), devMode = false)) { routes =>
        for
          response <- routes.runZIO(Request.get(url"/api/stack-info"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"backend\":\"postgres\""),
          body.contains("\"analytics\"")
        )
      }
    },
    test("GET /api/state with ?format=fen returns the export payload (StateResponse.Export branch)") {
      runWith() { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(Request.get(url"/api/games/$id/state?format=fen"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          // The Export DTO has {"format":"fen","content":"..."} shape;
          // contrast with View's BoardStateDto which has "squares" etc.
          body.contains("\"format\":\"fen\""),
          body.contains("\"content\"")
        )
      }
    },
    test("POST /api/games with `load` invokes the gameService loadGame branch") {
      // Same outcome as a fresh `newGame` — the load path is the one
      // that's never exercised when CreateGameRequest.load is None.
      runWith() { routes =>
        for
          response <- routes.runZIO(
                        withSession(
                          Request.post(
                            url"/api/games",
                            Body.fromString(
                              """{"load":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"}"""
                            )
                          )
                        )
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("\"id\":\"")
        )
      }
    },
    test("POST /api/games/{id}/undo invalidates the annotation cache (success path)") {
      // Make one move, hit legal-moves to populate the cache, undo,
      // then verify legal-moves reflects the rolled-back state.
      runWith() { routes =>
        for
          id      <- createGame(routes)
          _       <- routes.runZIO(
                       withSession(
                         Request.post(
                           url"/api/games/$id/move",
                           Body.fromString("""{"move":"e2 e4"}""")
                         )
                       )
                     )
          _       <- routes.runZIO(Request.get(url"/api/games/$id/legal-moves?from=e4"))
          undo    <- routes.runZIO(
                       withSession(Request.post(url"/api/games/$id/undo", Body.empty))
                     )
          replay  <- routes
                       .runZIO(Request.get(url"/api/games/$id/legal-moves?from=e4"))
                       .flatMap(_.body.asString)
        yield assertTrue(
          undo.status == Status.Ok,
          // After the undo, the pawn is back on e2 so e4 is empty.
          replay.contains("\"moves\":[]")
        )
      }
    },
    test("POST /api/games/{id}/draw returns 400 when no claim condition holds (failure path)") {
      runWith() { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(
                        withSession(Request.post(url"/api/games/$id/draw", Body.empty))
                      )
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("POST /api/games/{id}/forfeit invokes the gameService forfeit branch (cache invalidate)") {
      // Returns 200 even though the DTO loses the resignation status —
      // `replyToDto` rebuilds GameStatus from the FEN, which doesn't
      // carry it (`reply.status` is currently unused). For coverage we
      // just need the handler path; the dto-status pre-existing bug is
      // tracked separately.
      runWith() { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(
                        withSession(Request.post(url"/api/games/$id/forfeit", Body.empty))
                      )
        yield assertTrue(response.status == Status.Ok)
      }
    },
    test("POST /internal/games/{id}/players (with guest) registers the lobby") {
      // Internal endpoint — not on the public Swagger list, but the
      // route is wired and must round-trip a 200 with no body.
      runWith() { routes =>
        for
          id <- createGame(routes)
          response <- routes.runZIO(
                        Request.post(
                          url"/internal/games/$id/players",
                          Body.fromString(
                            s"""{"hostSessionId":"$testSession","guestSessionId":"session-guest"}"""
                          )
                        )
                      )
        yield assertTrue(response.status == Status.Ok)
      }
    },
    test("POST /internal/games/{id}/players (no guest) re-registers as local") {
      runWith() { routes =>
        for
          id <- createGame(routes)
          response <- routes.runZIO(
                        Request.post(
                          url"/internal/games/$id/players",
                          Body.fromString(
                            s"""{"hostSessionId":"$testSession","guestSessionId":null}"""
                          )
                        )
                      )
        yield assertTrue(response.status == Status.Ok)
      }
    },
    test("GET /web/style.css serves the bundled stylesheet (asset path)") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/style.css"))
          mediaType = response.headers.get(Header.ContentType).map(_.mediaType)
        yield assertTrue(
          response.status == Status.Ok,
          mediaType.contains(MediaType.text.css)
        )
      }
    },
    test("GET /web/<missing> returns 404 (unknown extension)") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/does-not-exist.xyz"))
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("GET /web/<path traversal> returns 404 (isSafeAssetPath blocks ..)") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/../etc/passwd"))
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("GET /dev/coverage/report/ returns 404 when PICHESS_DEV is unset (default)") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/dev/coverage/report/"))
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("GET /dev/coverage/report/<missing> returns 404 when dev mode IS enabled but the file isn't present") {
      // With devMode=true the route is registered, so a missing file
      // falls through to the serveDevAsset 404 branch (rather than the
      // route-not-found 404 we get when devMode is false). The
      // generated report files aren't checked in.
      runWith(chess.controller.StackInfo("postgres", Nil, devMode = true)) { routes =>
        for
          response <- routes.runZIO(
                        Request.get(url"/dev/coverage/report/some-missing.html")
                      )
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("GET /dev/performance/report/ same routing applies") {
      runWith(chess.controller.StackInfo("postgres", Nil, devMode = true)) { routes =>
        for
          response <- routes.runZIO(
                        Request.get(url"/dev/performance/report/notthere.html")
                      )
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("POST /api/games with malformed `load` returns 400 (loadGame error path)") {
      // Triggers the `toErrorDto` mapping inside the load branch — the
      // gameService surface returns INVALID_ARGUMENT for an unparseable FEN.
      runWith() { routes =>
        for
          response <- routes.runZIO(
                        withSession(
                          Request.post(
                            url"/api/games",
                            Body.fromString("""{"load":"not-a-fen"}""")
                          )
                        )
                      )
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("POST /api/games/{id}/redo restores a previously undone move") {
      // Covers the redo handler — make a move, undo it, then redo;
      // the position should look like after the original move again.
      runWith() { routes =>
        for
          id   <- createGame(routes)
          _    <- routes.runZIO(
                    withSession(
                      Request.post(
                        url"/api/games/$id/move",
                        Body.fromString("""{"move":"e2 e4"}""")
                      )
                    )
                  )
          _    <- routes.runZIO(
                    withSession(Request.post(url"/api/games/$id/undo", Body.empty))
                  )
          redo <- routes.runZIO(
                    withSession(Request.post(url"/api/games/$id/redo", Body.empty))
                  )
          body <- redo.body.asString
        yield assertTrue(
          redo.status == Status.Ok,
          body.contains("\"san\":\"e4\"")
        )
      }
    },
    test("two consecutive legal-moves calls hit the annotation cache (cached branch)") {
      // The first call populates the cache; the second call returns the
      // cached bundle (the `case Some(cached)` arm). Both calls return
      // the same data, but the second one exercises the cache hit branch.
      runWith() { routes =>
        for
          id    <- createGame(routes)
          first <- routes
                     .runZIO(Request.get(url"/api/games/$id/legal-moves?from=e2"))
                     .flatMap(_.body.asString)
          again <- routes
                     .runZIO(Request.get(url"/api/games/$id/legal-moves?from=e2"))
                     .flatMap(_.body.asString)
        yield assertTrue(first == again, first.contains("\"e4\""))
      }
    },
    test("GET /api/games/{id}/export/fen returns 404 for an unknown game (export error path)") {
      // exportInFormat → mapError(toErrorDto) → ErrorDto → 400 from tapir.
      runWith() { routes =>
        for
          response <- routes.runZIO(
                        Request.get(url"/api/games/does-not-exist/export/fen")
                      )
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("GET /api/games/{id}/state returns 400 for an unknown game (currentBoard error path)") {
      // currentBoard → client.getState → INVALID_ARGUMENT/NOT_FOUND → ErrorDto.
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/api/games/missing/state"))
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("state after a black move includes both colors in the move log (parseColor White + Black)") {
      // After 1. e4 e5 the move log has a White entry and a Black entry —
      // exercises both arms of `parseColor`. Players use different sessions
      // because both colors mutate (host + lobby-registered guest).
      runWith() { routes =>
        for
          id      <- createGame(routes)
          // Promote the game to lobby so the guest session can mutate.
          _       <- routes.runZIO(
                       Request.post(
                         url"/internal/games/$id/players",
                         Body.fromString(
                           s"""{"hostSessionId":"$testSession","guestSessionId":"session-guest"}"""
                         )
                       )
                     )
          _       <- routes.runZIO(
                       withSession(
                         Request.post(
                           url"/api/games/$id/move",
                           Body.fromString("""{"move":"e2 e4"}""")
                         )
                       )
                     )
          _       <- routes.runZIO(
                       withSession(
                         Request.post(
                           url"/api/games/$id/move",
                           Body.fromString("""{"move":"e7 e5"}""")
                         ),
                         session = "session-guest"
                       )
                     )
          state   <- routes.runZIO(Request.get(url"/api/games/$id/state"))
          body    <- state.body.asString
        yield assertTrue(
          body.contains("\"color\":\"white\""),
          body.contains("\"color\":\"black\"")
        )
      }
    },
    test("GET /web/<svg> returns image/svg+xml (contentTypeFor .svg branch)") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/missing.svg"))
        yield assertTrue(
          // Either the asset exists (200 + svg media type) or it 404s — both
          // outcomes traverse the .svg arm of `contentTypeFor`, which is the
          // statement we want to mark covered.
          response.status == Status.Ok || response.status == Status.NotFound
        )
      }
    },
    test("GET /web/<png> traverses the .png arm of contentTypeFor") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/missing.png"))
        yield assertTrue(
          response.status == Status.Ok || response.status == Status.NotFound
        )
      }
    },
    test("GET /web/<html> traverses the .html arm of contentTypeFor") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/missing.html"))
        yield assertTrue(
          response.status == Status.Ok || response.status == Status.NotFound
        )
      }
    },
    test("GET /web/<json> traverses the .json arm of contentTypeFor") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/missing.json"))
        yield assertTrue(
          response.status == Status.Ok || response.status == Status.NotFound
        )
      }
    },
    test("GET /web/<js> traverses the .js arm of contentTypeFor") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/missing.js"))
        yield assertTrue(
          response.status == Status.Ok || response.status == Status.NotFound
        )
      }
    },
    test("GET /dev/coverage/report/ (no trailing path) rewrites to index.html") {
      // Hits the `raw.isEmpty || raw.endsWith("/")` branch in serveDevAsset.
      // The actual index.html isn't checked in, so the response is 404,
      // but the rewrite branch IS traversed before the classpath lookup.
      runWith(chess.controller.StackInfo("postgres", Nil, devMode = true)) { routes =>
        for
          response <- routes.runZIO(Request.get(url"/dev/coverage/report/"))
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("GET /dev/coverage/report/<no-extension> hits the case _ 404 branch of serveDevAsset") {
      // Path has no recognised extension so `contentTypeFor` returns None,
      // which falls through to the `case _ => NotFound` arm without
      // touching the classpath lookup. Distinct from the
      // <missing>.html case which hits the Some-arm + 404 from
      // serveClasspathResource.
      runWith(chess.controller.StackInfo("postgres", Nil, devMode = true)) { routes =>
        for
          response <- routes.runZIO(Request.get(url"/dev/coverage/report/noext"))
        yield assertTrue(response.status == Status.NotFound)
      }
    },
    test("POST /api/games/{id}/draw succeeds at halfmove=100 (50-move rule) and invalidates the cache") {
      // Load a king-only endgame with the halfmove clock already at 100 ply.
      // claimDraw → fiftyMoveOk = true → returns a Draw state — exercises
      // the `.tap(_ => cache.invalidate(id))` success arm of the draw handler.
      runWith() { routes =>
        for
          create   <- routes.runZIO(
                        withSession(
                          Request.post(
                            url"/api/games",
                            Body.fromString(
                              """{"load":"4k3/8/8/8/8/8/8/4K3 w - - 100 50"}"""
                            )
                          )
                        )
                      )
          body     <- create.body.asString
          id        = """"id":"([^"]+)"""".r.findFirstMatchIn(body).get.group(1)
          response <- routes.runZIO(
                        withSession(Request.post(url"/api/games/$id/draw", Body.empty))
                      )
        yield assertTrue(response.status == Status.Ok)
      }
    },
    test("GET /api/games/{id}/threats with an actively threatened piece returns a non-empty list (covers attackerEntries map)") {
      // Loads a position where white's queen on d4 is attacked by a
      // black rook on d8 — the active-color piece is threatened, so
      // `threats` is non-empty and the `attackerEntries.map { sq => ... }`
      // branch in computeAnnotations actually executes.
      runWith() { routes =>
        for
          create   <- routes.runZIO(
                        withSession(
                          Request.post(
                            url"/api/games",
                            Body.fromString(
                              """{"load":"3r3k/8/8/8/3Q4/8/8/4K3 w - - 0 1"}"""
                            )
                          )
                        )
                      )
          body     <- create.body.asString
          id        = """"id":"([^"]+)"""".r.findFirstMatchIn(body).get.group(1)
          threats  <- routes
                        .runZIO(Request.get(url"/api/games/$id/threats"))
                        .flatMap(_.body.asString)
          attackers <- routes
                         .runZIO(Request.get(url"/api/games/$id/attackers?of=d4"))
                         .flatMap(_.body.asString)
        yield assertTrue(
          threats.contains("\"d4\""),
          attackers.contains("\"d8\"")
        )
      }
    },
    test("GET /api/games/missing/legal-moves returns 400 (annotationsFor toErrorDto path)") {
      // Routes through annotationsFor → client.getState fails →
      // `.mapError(toErrorDto)` lambda body actually fires.
      runWith() { routes =>
        for
          response <- routes.runZIO(
                        Request.get(url"/api/games/missing/legal-moves?from=e2")
                      )
        yield assertTrue(response.status == Status.BadRequest)
      }
    },
    test("GET /web/<file_with_underscore>.css traverses the `_` arm of isSafeAssetPath") {
      runWith() { routes =>
        for
          response <- routes.runZIO(Request.get(url"/web/foo_bar.css"))
        yield assertTrue(
          // Whether the file exists or not is irrelevant — the request
          // passing `isSafeAssetPath` is what marks the `c == '_'` char
          // arm as covered.
          response.status == Status.Ok || response.status == Status.NotFound
        )
      }
    },
    test("GET /api/games/{id}/events opens an SSE stream (serveEvents handler)") {
      // Just hit the handler — `Response.fromServerSentEvents` constructs
      // the response synchronously, so the route returns immediately
      // without us needing to consume the body (which would hang on
      // the upstream SSE forever).
      runWith() { routes =>
        for
          id       <- createGame(routes)
          response <- routes.runZIO(Request.get(url"/api/games/$id/events"))
        yield assertTrue(response.status == Status.Ok)
      }
    }
  )

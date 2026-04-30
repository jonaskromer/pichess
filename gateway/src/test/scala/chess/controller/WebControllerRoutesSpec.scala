package chess.controller

import chess.model.{GameError, GameEvent, GameId, GameSnapshot, SessionState}
import chess.model.board.{DrawReason, GameState, GameStatus, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.repository.InMemoryGameRepository
import chess.service.{GameService, GameServiceLive}
import zio.*
import zio.http.*
import zio.stream.SubscriptionRef
import zio.test.*

object WebControllerRoutesSpec extends ZIOSpecDefault:

  private val appLayer: ULayer[GameService] =
    InMemoryGameRepository.layer >>> GameServiceLive.layer

  private def withRoutes =
    for
      gs <- ZIO.service[GameService]
      event <- gs.newGame()
      session <- SubscriptionRef.make(
        SessionState(GameSnapshot.fresh(event.gameId, event.initialState))
      )
      shutdown <- Promise.make[Nothing, Unit]
      routes = WebController.routes(gs, session, shutdown)
    yield (routes, session, shutdown)

  def spec = suite("WebController routes")(
    test("GET /api/state returns JSON with activeColor") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/state"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""activeColor":"white""""),
        body.contains(""""status":{"kind":"playing"""")
      )
    },
    test("GET /api/state?format=view also returns the UI projection") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/state?format=view"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""activeColor":"white"""")
      )
    },
    test("GET /api/state?format=fen returns an ExportResponse envelope") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/state?format=fen"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""format":"fen""""),
        body.contains("KQkq")
      )
    },
    test("GET /api/state?format=pgn returns an ExportResponse envelope") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/state?format=pgn"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""format":"pgn"""")
      )
    },
    test("GET /api/state?format=json returns an ExportResponse envelope") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/state?format=json"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""format":"json"""")
      )
    },
    test("GET /api/state?format=xyz returns 400 with an error") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/state?format=pdf"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("Unknown format")
      )
    },
    test("POST /api/move applies a valid move") {
      for
        (routes, session, _) <- withRoutes
        response <- routes.runZIO(
          Request.post(url"/api/move", Body.fromString("""{"move":"e2 e4"}"""))
        )
        body <- response.body.asString
        s <- session.get
      yield assertTrue(
        response.status == Status.Ok,
        s.state.board.get(Position('e', 4)) == Some(
          Piece(Color.White, PieceType.Pawn)
        )
      )
    },
    test("POST /api/move returns error for invalid move") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(
          Request.post(url"/api/move", Body.fromString("""{"move":"e2 e5"}"""))
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("cannot move to")
      )
    },
    test("POST /api/move returns error for missing move field") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(
          Request.post(url"/api/move", Body.fromString("""{"foo":"bar"}"""))
        )
      yield assertTrue(response.status == Status.BadRequest)
    },
    test("POST /api/new resets the game") {
      for
        (routes, session, _) <- withRoutes
        _ <- routes.runZIO(
          Request.post(url"/api/move", Body.fromString("""{"move":"e2 e4"}"""))
        )
        _ <- routes.runZIO(Request.post(url"/api/new", Body.empty))
        s <- session.get
      yield assertTrue(
        s.state.activeColor == Color.White,
        s.moves.isEmpty,
        s.state.board == GameState.initial.board
      )
    },
    test("POST /api/quit sets the shutdown promise") {
      for
        (routes, _, shutdown) <- withRoutes
        response <- routes.runZIO(Request.post(url"/api/quit", Body.empty))
        body <- response.body.asString
        isDone <- shutdown.isDone
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""quit":true"""),
        isDone
      )
    },
    test("GET /api/events returns an SSE stream of state events") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/events"))
      yield assertTrue(
        response.status == Status.Ok,
        response.headers
          .get(Header.ContentType)
          .exists(_.mediaType == MediaType.text.`event-stream`)
      )
    },
    test("POST /api/move surfaces a body I/O failure as 5xx") {
      // Tapir treats a failed body stream as an unhandled server exception
      // (5xx), not as a decode failure (4xx). Malformed-JSON cases are
      // covered by the "missing move field" / "invalid move" tests above,
      // which continue to return 400.
      val brokenBody = Body.fromStreamChunked(
        zio.stream.ZStream.fail(new RuntimeException("body boom"))
      )
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.post(url"/api/move", brokenBody))
      yield assertTrue(response.status.isError && !response.status.isSuccess)
    },
    test("POST /api/undo reverts the last move") {
      for
        (routes, session, _) <- withRoutes
        _ <- routes.runZIO(
          Request.post(url"/api/move", Body.fromString("""{"move":"e2 e4"}"""))
        )
        response <- routes.runZIO(Request.post(url"/api/undo", Body.empty))
        s <- session.get
      yield assertTrue(
        response.status == Status.Ok,
        s.moves.isEmpty,
        s.state == GameState.initial
      )
    },
    test("POST /api/redo reapplies an undone move") {
      for
        (routes, session, _) <- withRoutes
        _ <- routes.runZIO(
          Request.post(url"/api/move", Body.fromString("""{"move":"e2 e4"}"""))
        )
        _ <- routes.runZIO(Request.post(url"/api/undo", Body.empty))
        response <- routes.runZIO(Request.post(url"/api/redo", Body.empty))
        s <- session.get
      yield assertTrue(
        response.status == Status.Ok,
        s.moves.length == 1,
        s.state.board.get(Position('e', 4)) == Some(
          Piece(Color.White, PieceType.Pawn)
        )
      )
    },
    test("POST /api/undo returns error when nothing to undo") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.post(url"/api/undo", Body.empty))
      yield assertTrue(response.status == Status.BadRequest)
    },
    test("POST /api/forfeit ends the game with the opponent as winner") {
      for
        (routes, session, _) <- withRoutes
        response <- routes.runZIO(Request.post(url"/api/forfeit", Body.empty))
        body <- response.body.asString
        s <- session.get
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""kind":"resignation""""),
        body.contains(""""winner":"black""""),
        s.state.status == GameStatus.Resignation(Color.Black)
      )
    },
    test("POST /api/forfeit returns 400 when the game is already over") {
      for
        (routes, _, _) <- withRoutes
        _ <- routes.runZIO(Request.post(url"/api/forfeit", Body.empty))
        response <- routes.runZIO(Request.post(url"/api/forfeit", Body.empty))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("already over")
      )
    },
    test("POST /api/draw returns error when clock is below 100") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.post(url"/api/draw", Body.empty))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("Cannot claim draw")
      )
    },
    test("POST /api/draw succeeds when clock is 100") {
      import chess.model.board.Move
      val drawableState = GameState(
        Map(
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('e', 8) -> Piece(Color.Black, PieceType.King)
        ),
        Color.White,
        halfmoveClock = 100
      )
      val dummyMove = Move(Position('e', 1), Position('e', 1))
      for
        gs <- ZIO.service[GameService]
        (routes, session, _) <- withRoutes
        gameId <- session.get.map(_.gameId)
        _ <- gs.saveState(gameId, drawableState)
        _ <- session.update(st =>
          st.copy(game =
            st.game.copy(
              history = List((dummyMove, drawableState))
            )
          )
        )
        response <- routes.runZIO(Request.post(url"/api/draw", Body.empty))
        s <- session.get
      yield assertTrue(
        response.status == Status.Ok,
        s.state.status == GameStatus.Draw(DrawReason.FiftyMoveRule)
      )
    },
    test("POST /api/load replays a PGN into the session") {
      val pgn = "1. e4 e5 2. Nf3 Nc6 *"
      for
        (routes, session, _) <- withRoutes
        response <- routes.runZIO(
          Request.post(
            url"/api/load",
            Body.fromString(s"""{"raw":"$pgn"}""")
          )
        )
        body <- response.body.asString
        s <- session.get
      yield assertTrue(
        response.status == Status.Ok,
        // After e4 e5 Nf3 Nc6, white knight on f3 and e-pawns have moved.
        s.state.board.get(Position('f', 3)) == Some(
          Piece(Color.White, PieceType.Knight)
        ),
        s.state.board.get(Position('c', 6)) == Some(
          Piece(Color.Black, PieceType.Knight)
        ),
        body.contains(""""activeColor":"white"""")
      )
    },
    test("POST /api/load rejects garbage with 400") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(
          Request.post(
            url"/api/load",
            Body.fromString("""{"raw":"not a game"}""")
          )
        )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains(""""error":""")
      )
    },
    test("GET /api/export/fen returns the current position") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/export/fen"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""format":"fen""""),
        // The initial FEN ends with "KQkq - 0 1".
        body.contains("KQkq")
      )
    },
    test("GET /api/export/pgn returns a PGN envelope") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/export/pgn"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""format":"pgn"""")
      )
    },
    test("GET /api/export/json returns a JSON envelope") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/export/json"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(""""format":"json"""")
      )
    },
    test("GET /api/export/unknown returns 400 with an error message") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/api/export/pdf"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("Unknown format")
      )
    },
    test("POST /api/redo returns error when nothing to redo") {
      // Drives the redo error → toErrorDto path in WebController.
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.post(url"/api/redo", Body.empty))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("Nothing to redo")
      )
    },
    test("GET /web/main.js serves the JS bundle or 404 if absent") {
      // Exercises the raw zio-http serveJsBundle handler. The asset is only
      // present on the classpath in production builds (managed resources from
      // webUi/fastLinkJS); in unit tests it's typically absent, so we accept
      // either Ok with body or NotFound — both branches are valid responses.
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/main.js"))
      yield assertTrue(
        response.status == Status.Ok || response.status == Status.NotFound
      )
    },
    test("GET /web/peach.png serves the rasterised favicon") {
      // The PNG is the favicon for browsers (Safari) that don't render
      // <symbol>/<use>-bearing SVGs as icons. Body is binary, so we just
      // sanity-check the response status and content type plus a minimum
      // body length.
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/peach.png"))
        bytes <- response.body.asArray
      yield assertTrue(
        response.status == Status.Ok,
        response.headers
          .get(Header.ContentType)
          .exists(h =>
            h.mediaType.mainType == "image" && h.mediaType.subType == "png"
          ),
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        bytes.length > 8,
        bytes(0) == 0x89.toByte,
        bytes(1) == 0x50.toByte, // 'P'
        bytes(2) == 0x4e.toByte, // 'N'
        bytes(3) == 0x47.toByte // 'G'
      )
    },
    test("GET /web/peach.svg serves the brand logo SVG") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/peach.svg"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        response.headers
          .get(Header.ContentType)
          .exists(h =>
            h.mediaType.mainType == "image" &&
              h.mediaType.subType == "svg+xml"
          ),
        body.contains("""id="peach"""")
      )
    },
    test("GET /web/pieces/pawn.svg serves a unified piece SVG") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/pieces/pawn.svg"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        response.headers
          .get(Header.ContentType)
          .exists(h =>
            h.mediaType.mainType == "image" &&
              h.mediaType.subType == "svg+xml"
          ),
        // Sanity: the body should be the unified, var-driven SVG, not a raw
        // light/dark variant. The CSS-variable references uniquely identify it,
        // and the composite `<symbol id="pawn">` is what external <use> targets.
        body.contains("var(--piece-primary"),
        body.contains("<symbol id=\"pawn\"")
      )
    },
    test("GET /web/pieces/missing.svg returns 404 when the file is absent") {
      // 'missing' passes the allow-list but isn't on the classpath; this
      // exercises the stream==null branch in serveClasspathResource.
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/pieces/missing.svg"))
      yield assertTrue(response.status == Status.NotFound)
    },
    test(
      "GET /web/pieces/<bad-name> returns 404 without touching the classpath"
    ) {
      // The asset path validator rejects `..` segments and characters
      // outside [A-Za-z0-9._-], so a URL-encoded traversal attempt is 404.
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/pieces/..%2Fmain.js"))
      yield assertTrue(response.status == Status.NotFound)
    },
    test("GET /web/foo.txt returns 404 — unknown extension is not served") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/foo.txt"))
      yield assertTrue(response.status == Status.NotFound)
    },
    test("GET /web/foo..svg returns 404 — `..` anywhere is rejected") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/foo..svg"))
      yield assertTrue(response.status == Status.NotFound)
    },
    test("GET /web/foo bar.svg returns 404 — disallowed character") {
      // `%20` is a space; the validator rejects it before classpath lookup.
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/foo%20bar.svg"))
      yield assertTrue(response.status == Status.NotFound)
    },
    test(
      "GET /web/style.css serves the stylesheet with text/css content type"
    ) {
      // Even though HtmlPage also inlines the CSS, the file exists in
      // resources/web/ and the generic asset endpoint serves it. Exercises
      // the css branch of contentTypeFor.
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/web/style.css"))
      yield assertTrue(
        response.status == Status.Ok,
        response.headers
          .get(Header.ContentType)
          .exists(h =>
            h.mediaType.mainType == "text" && h.mediaType.subType == "css"
          )
      )
    },
    test("GET / serves the HTML shell") {
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.get(url"/"))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        response.headers
          .get(Header.ContentType)
          .exists(_.mediaType == MediaType.text.html),
        body.contains("<html")
      )
    },
    suite("postNew error mapping")(
      test("surfaces a GameService.newGame failure as 400 with the message") {
        // Uses a stub GameService whose newGame fails, so the
        // `mapError(err => ErrorDto(err.message))` branch in postNew runs.
        val failingGs: GameService = new GameService:
          def newGame(): IO[GameError, GameEvent.GameStarted] =
            ZIO.fail(GameError.InvalidMove("repository unavailable"))
          def loadGame(input: String) =
            ZIO.fail(GameError.ParseError("not used"))
          def makeMove(id: GameId, rawInput: String) =
            ZIO.fail(GameError.GameNotFound(id))
          def getState(id: GameId) = ZIO.succeed(None)
          def saveState(id: GameId, state: GameState) = ZIO.unit

        for
          session <- SubscriptionRef.make(
            SessionState(GameSnapshot.fresh("dummy", GameState.initial))
          )
          shutdown <- Promise.make[Nothing, Unit]
          routes = WebController.routes(failingGs, session, shutdown)
          response <- routes.runZIO(Request.post(url"/api/new", Body.empty))
          body <- response.body.asString
        yield assertTrue(
          response.status == Status.BadRequest,
          body.contains("repository unavailable")
        )
      }
    )
  ).provide(appLayer, Scope.default)

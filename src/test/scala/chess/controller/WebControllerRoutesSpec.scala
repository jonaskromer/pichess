package chess.controller

import chess.model.{GameSnapshot, SessionState}
import chess.model.board.{DrawReason, GameState, GameStatus, Position}
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
        SessionState(GameSnapshot(event.gameId, event.initialState))
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
        body.contains(""""activeColor":"white"""")
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
    test("POST /api/move surfaces a body-decode error as 400") {
      val brokenBody = Body.fromStreamChunked(
        zio.stream.ZStream.fail(new RuntimeException("body boom"))
      )
      for
        (routes, _, _) <- withRoutes
        response <- routes.runZIO(Request.post(url"/api/move", brokenBody))
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("body boom")
      )
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
    }
  ).provide(appLayer, Scope.default)

package chess.controller

import chess.model.SessionState
import chess.model.board.{GameState, Position}
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
        SessionState(event.gameId, event.initialState, Nil, None)
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
        body.contains("activeColor")
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
        body.contains("error")
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
        s.moveLog.isEmpty,
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
        body.contains("quit"),
        isDone
      )
    }
  ).provide(appLayer, Scope.default)

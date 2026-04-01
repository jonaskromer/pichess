package chess.controller

import chess.model.SessionState
import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.repository.InMemoryGameRepository
import chess.service.{GameService, GameServiceLive}
import zio.*
import zio.stream.SubscriptionRef
import zio.test.*

object GameControllerSpec extends ZIOSpecDefault:

  private val appLayer: ULayer[GameService] =
    InMemoryGameRepository.layer >>> GameServiceLive.layer

  private def withSession =
    for
      gs <- ZIO.service[GameService]
      event <- gs.newGame()
      session <- SubscriptionRef.make(
        SessionState(event.gameId, event.initialState, Nil, None)
      )
    yield (gs, session)

  def spec = suite("GameController.makeMove")(
    test("update session state after a valid move") {
      for
        (gs, session) <- withSession
        _ <- GameController.makeMove(gs, session, "e2 e4")
        s <- session.get
      yield assertTrue(
        s.state.board.get(Position('e', 4)) == Some(
          Piece(Color.White, PieceType.Pawn)
        ),
        s.state.activeColor == Color.Black
      )
    },
    test("append SAN to the move log") {
      for
        (gs, session) <- withSession
        _ <- GameController.makeMove(gs, session, "e2 e4")
        s <- session.get
      yield assertTrue(
        s.moveLog == List((Color.White, "e4"))
      )
    },
    test("clear error on successful move") {
      for
        (gs, session) <- withSession
        _ <- session.update(_.copy(error = Some("previous error")))
        _ <- GameController.makeMove(gs, session, "e2 e4")
        s <- session.get
      yield assertTrue(s.error.isEmpty)
    },
    test("fail for an illegal move") {
      for
        (gs, session) <- withSession
        exit <- GameController.makeMove(gs, session, "e2 e5").exit
      yield assertTrue(exit.isFailure)
    },
    test("fail for a parse error") {
      for
        (gs, session) <- withSession
        exit <- GameController.makeMove(gs, session, "garbage").exit
      yield assertTrue(exit.isFailure)
    },
    test("accept SAN notation") {
      for
        (gs, session) <- withSession
        _ <- GameController.makeMove(gs, session, "Nf3")
        s <- session.get
      yield assertTrue(
        s.state.board.get(Position('f', 3)) == Some(
          Piece(Color.White, PieceType.Knight)
        ),
        s.moveLog == List((Color.White, "Nf3"))
      )
    },
    test("chain multiple moves") {
      for
        (gs, session) <- withSession
        _ <- GameController.makeMove(gs, session, "e4")
        _ <- GameController.makeMove(gs, session, "e5")
        s <- session.get
      yield assertTrue(
        s.moveLog == List((Color.White, "e4"), (Color.Black, "e5")),
        s.state.activeColor == Color.White
      )
    }
  ).provide(appLayer)

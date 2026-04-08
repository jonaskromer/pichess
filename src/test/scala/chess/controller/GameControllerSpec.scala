package chess.controller

import chess.model.{GameSnapshot, SessionState}
import chess.model.board.{DrawReason, GameState, GameStatus, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.notation.SanSerializer
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
        SessionState(GameSnapshot(event.gameId, event.initialState))
      )
    yield (gs, session)

  def spec = suite("GameController")(
    suite("makeMove")(
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
      test("append Move to the moves list") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e2 e4")
          s <- session.get
        yield assertTrue(
          s.moves.length == 1,
          s.moves.head == Move(Position('e', 2), Position('e', 4))
        )
      },
      test("derive correct SAN from moves") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e2 e4")
          s <- session.get
          sanLog <- SanSerializer.deriveMoveLog(s.initialState, s.history)
        yield assertTrue(
          sanLog == List((Color.White, "e4"))
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
      test("clear redo stack on new move") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e2 e4")
          _ <- GameController.undo(gs, session)
          _ <- GameController.makeMove(gs, session, "d2 d4")
          s <- session.get
        yield assertTrue(s.redoStack.isEmpty)
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
          sanLog <- SanSerializer.deriveMoveLog(s.initialState, s.history)
        yield assertTrue(
          s.state.board.get(Position('f', 3)) == Some(
            Piece(Color.White, PieceType.Knight)
          ),
          sanLog == List((Color.White, "Nf3"))
        )
      },
      test("chain multiple moves") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e4")
          _ <- GameController.makeMove(gs, session, "e5")
          s <- session.get
          sanLog <- SanSerializer.deriveMoveLog(s.initialState, s.history)
        yield assertTrue(
          sanLog == List((Color.White, "e4"), (Color.Black, "e5")),
          s.state.activeColor == Color.White
        )
      }
    ),
    suite("undo")(
      test("restore previous state") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e2 e4")
          _ <- GameController.undo(gs, session)
          s <- session.get
        yield assertTrue(
          s.state == GameState.initial,
          s.moves.isEmpty
        )
      },
      test("push undone move to redo stack") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e2 e4")
          _ <- GameController.undo(gs, session)
          s <- session.get
        yield assertTrue(
          s.redoStack.map(_._1) == List(Move(Position('e', 2), Position('e', 4)))
        )
      },
      test("fail when no moves to undo") {
        for
          (gs, session) <- withSession
          exit <- GameController.undo(gs, session).exit
        yield assertTrue(exit.isFailure)
      },
      test("undo multiple moves in sequence") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e2 e4")
          _ <- GameController.makeMove(gs, session, "e7 e5")
          _ <- GameController.undo(gs, session)
          s1 <- session.get
          _ <- GameController.undo(gs, session)
          s2 <- session.get
        yield assertTrue(
          s1.moves.length == 1,
          s1.state.activeColor == Color.Black,
          s2.moves.isEmpty,
          s2.state == GameState.initial
        )
      }
    ),
    suite("redo")(
      test("reapply undone move") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e2 e4")
          _ <- GameController.undo(gs, session)
          _ <- GameController.redo(gs, session)
          s <- session.get
        yield assertTrue(
          s.moves.length == 1,
          s.state.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          ),
          s.redoStack.isEmpty
        )
      },
      test("fail when no moves to redo") {
        for
          (gs, session) <- withSession
          exit <- GameController.redo(gs, session).exit
        yield assertTrue(exit.isFailure)
      },
      test("redo multiple moves in sequence") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "e2 e4")
          _ <- GameController.makeMove(gs, session, "e7 e5")
          _ <- GameController.undo(gs, session)
          _ <- GameController.undo(gs, session)
          _ <- GameController.redo(gs, session)
          _ <- GameController.redo(gs, session)
          s <- session.get
        yield assertTrue(
          s.moves.length == 2,
          s.state.activeColor == Color.White
        )
      }
    ),
    suite("claimDraw")(
      test("fail when halfmove clock is below 100") {
        for
          (gs, session) <- withSession
          exit <- GameController.claimDraw(gs, session).exit
        yield assertTrue(exit.isFailure)
      },
      test("fail with descriptive message about remaining moves") {
        for
          (gs, session) <- withSession
          err <- GameController.claimDraw(gs, session).flip
        yield assertTrue(err.message.contains("Cannot claim draw"))
      },
      test("succeed when halfmove clock reaches 100") {
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
          (gs, session) <- withSession
          gameId <- session.get.map(_.gameId)
          _ <- gs.saveState(gameId, drawableState)
          _ <- session.update(st =>
            st.copy(game = st.game.copy(
              history = List((dummyMove, drawableState))
            ))
          )
          _ <- GameController.claimDraw(gs, session)
          s <- session.get
        yield assertTrue(
          s.state.status == GameStatus.Draw(DrawReason.FiftyMoveRule)
        )
      },
      test("fail when game is already over") {
        val state = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          Color.White,
          halfmoveClock = 100,
          status = GameStatus.Checkmate(Color.White)
        )
        val dummyMove = Move(Position('e', 1), Position('e', 1))
        for
          (gs, session) <- withSession
          _ <- session.update(st =>
            st.copy(game = st.game.copy(
              history = List((dummyMove, state))
            ))
          )
          exit <- GameController.claimDraw(gs, session).exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("threefold repetition")(
      test("claim draw after position occurs 3 times") {
        // Initial → Nf3 → Nf6 → Ng1 → Ng8 (back to initial) → Nf3 → Nf6 → Ng1 → Ng8 (3rd time)
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "Nf3")
          _ <- GameController.makeMove(gs, session, "Nf6")
          _ <- GameController.makeMove(gs, session, "Ng1")
          _ <- GameController.makeMove(gs, session, "Ng8")
          _ <- GameController.makeMove(gs, session, "Nf3")
          _ <- GameController.makeMove(gs, session, "Nf6")
          _ <- GameController.makeMove(gs, session, "Ng1")
          _ <- GameController.makeMove(gs, session, "Ng8")
          _ <- GameController.claimDraw(gs, session)
          s <- session.get
        yield assertTrue(
          s.state.status == GameStatus.Draw(DrawReason.ThreefoldRepetition)
        )
      },
      test("reject claim when position has not occurred 3 times") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "Nf3")
          _ <- GameController.makeMove(gs, session, "Nf6")
          _ <- GameController.makeMove(gs, session, "Ng1")
          _ <- GameController.makeMove(gs, session, "Ng8")
          exit <- GameController.claimDraw(gs, session).exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("fivefold repetition")(
      test("automatic draw after position occurs 5 times") {
        for
          (gs, session) <- withSession
          _ <- GameController.makeMove(gs, session, "Nf3")
          _ <- GameController.makeMove(gs, session, "Nf6")
          _ <- GameController.makeMove(gs, session, "Ng1")
          _ <- GameController.makeMove(gs, session, "Ng8") // 2nd
          _ <- GameController.makeMove(gs, session, "Nf3")
          _ <- GameController.makeMove(gs, session, "Nf6")
          _ <- GameController.makeMove(gs, session, "Ng1")
          _ <- GameController.makeMove(gs, session, "Ng8") // 3rd
          _ <- GameController.makeMove(gs, session, "Nf3")
          _ <- GameController.makeMove(gs, session, "Nf6")
          _ <- GameController.makeMove(gs, session, "Ng1")
          _ <- GameController.makeMove(gs, session, "Ng8") // 4th
          _ <- GameController.makeMove(gs, session, "Nf3")
          _ <- GameController.makeMove(gs, session, "Nf6")
          _ <- GameController.makeMove(gs, session, "Ng1")
          _ <- GameController.makeMove(gs, session, "Ng8") // 5th
          s <- session.get
        yield assertTrue(
          s.state.status == GameStatus.Draw(DrawReason.FivefoldRepetition)
        )
      }
    )
  ).provide(appLayer)

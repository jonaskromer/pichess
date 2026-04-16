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
        SessionState(GameSnapshot.fresh(event.gameId, event.initialState))
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
          s.redoStack.map(_._1) == List(
            Move(Position('e', 2), Position('e', 4))
          )
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
            st.copy(game =
              st.game.copy(
                history = List((dummyMove, drawableState))
              )
            )
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
            st.copy(game =
              st.game.copy(
                history = List((dummyMove, state))
              )
            )
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
    ),
    suite("repetition with en passant perturbation")(
      test(
        "threefold claim succeeds after early pawn moves precede knight cycle"
      ) {
        // 1.e4 e5 sets and clears ep targets, then pure knight cycling cycles
        // through the same position (with e-pawns advanced, ep=None)
        // three times. After ply 12, white claims threefold.
        val moves = List(
          "e4",
          "e5",
          "Nf3",
          "Nc6", // position X: white to move, e-pawns on e4/e5, knights out
          "Ng1",
          "Nb8", // position Y: white to move, knights back
          "Nf3",
          "Nc6", // X again (2nd)
          "Ng1",
          "Nb8", // Y again (2nd)
          "Nf3",
          "Nc6" // X for the 3rd time
        )
        for
          (gs, session) <- withSession
          _ <- ZIO.foreach(moves)(m => GameController.makeMove(gs, session, m))
          _ <- GameController.claimDraw(gs, session)
          s <- session.get
        yield assertTrue(
          s.state.status == GameStatus.Draw(DrawReason.ThreefoldRepetition)
        )
      },
      test(
        "countCurrentPosition returns 3 when initial position recurs twice"
      ) {
        // Knight shuffle with no pawn moves: position after each full cycle
        // equals GameState.initial (same board, same active color, same
        // castling, no ep target ever set).
        val moves = List(
          "Nf3",
          "Nc6",
          "Ng1",
          "Nb8", // cycle 1: back to initial
          "Nf3",
          "Nc6",
          "Ng1",
          "Nb8" // cycle 2: back to initial, total 3 occurrences
        )
        for
          (gs, session) <- withSession
          _ <- ZIO.foreach(moves)(m => GameController.makeMove(gs, session, m))
          s <- session.get
        yield assertTrue(
          GameController.countCurrentPosition(s.game) == 3,
          // Position-wise identical to initial (ignores halfmove/fullmove clocks)
          chess.codec.FenSerializer.positionKey(s.state) ==
            chess.codec.FenSerializer.positionKey(GameState.initial),
          // Halfmove clock has advanced (8 non-pawn, non-capture moves)
          s.state.halfmoveClock == 8
        )
      }
    ),
    suite("undo/redo interaction with repetition counter")(
      test("claimDraw fails after undo, succeeds after redo") {
        // 8-ply knight shuffle: initial position occurs 3 times (at start,
        // after ply 4, after ply 8). claimDraw should succeed at ply 8.
        // After undo, current position is ply 7 (C: white knight back, black
        // knight on c6) which has occurred only twice → claim fails.
        // Redo restores the threefold situation → claim succeeds.
        val setup = List(
          "Nf3",
          "Nc6",
          "Ng1",
          "Nb8",
          "Nf3",
          "Nc6",
          "Ng1",
          "Nb8"
        )
        for
          (gs, session) <- withSession
          _ <- ZIO.foreach(setup)(m => GameController.makeMove(gs, session, m))
          afterAll <- session.get
          _ <- GameController.undo(gs, session)
          afterUndo <- session.get
          claimAfterUndo <- GameController.claimDraw(gs, session).exit
          _ <- GameController.redo(gs, session)
          afterRedo <- session.get
          _ <- GameController.claimDraw(gs, session)
          afterClaim <- session.get
        yield assertTrue(
          GameController.countCurrentPosition(afterAll.game) == 3,
          GameController.countCurrentPosition(afterUndo.game) == 2,
          claimAfterUndo.isFailure,
          GameController.countCurrentPosition(afterRedo.game) == 3,
          afterClaim.state.status ==
            GameStatus.Draw(DrawReason.ThreefoldRepetition)
        )
      },
      test("fivefold auto-draw reverts to Playing after undo") {
        // Four full cycles of Nf3/Nf6/Ng1/Ng8 place initial position at 5×
        // occurrence, triggering auto-draw on the final move. Undoing that
        // move drops back to ply 15 — position C with 4 occurrences — where
        // status is Playing and auto-draw is not set.
        val cycles =
          List.fill(4)(List("Nf3", "Nf6", "Ng1", "Ng8")).flatten
        for
          (gs, session) <- withSession
          _ <- ZIO.foreach(cycles)(m => GameController.makeMove(gs, session, m))
          afterAll <- session.get
          _ <- GameController.undo(gs, session)
          afterUndo <- session.get
        yield assertTrue(
          afterAll.state.status ==
            GameStatus.Draw(DrawReason.FivefoldRepetition),
          afterUndo.state.status == GameStatus.Playing,
          // Position after undo is ply 15 (white played Ng1, black's turn).
          // This position has occurred 4 times: plies 3, 7, 11, 15.
          GameController.countCurrentPosition(afterUndo.game) == 4
        )
      }
    ),
    suite("50-move rule via halfmove accumulation")(
      test(
        "halfmove clock increments once per non-pawn, non-capture move"
      ) {
        // Four knight moves (no pawns, no captures) → halfmoveClock advances
        // by one each ply.
        val moves = List("Nf3", "Nc6", "Nc3", "Nf6")
        for
          (gs, session) <- withSession
          _ <- ZIO.foreach(moves)(m =>
            GameController.makeMove(gs, session, m)
          )
          s <- session.get
        yield assertTrue(s.state.halfmoveClock == 4)
      },
      test("halfmove clock resets to zero on a pawn move") {
        val moves = List("Nf3", "Nc6", "Nc3", "Nf6", "e4")
        for
          (gs, session) <- withSession
          _ <- ZIO.foreach(moves)(m =>
            GameController.makeMove(gs, session, m)
          )
          s <- session.get
        yield assertTrue(s.state.halfmoveClock == 0)
      },
      test("halfmove clock resets to zero on a capturing move") {
        // 1.Nf3 Nc6 2.Nc3 d5 3.Nxd5 captures the pawn → halfmove resets.
        val moves = List("Nf3", "Nc6", "Nc3", "d5", "Nxd5")
        for
          (gs, session) <- withSession
          _ <- ZIO.foreach(moves)(m =>
            GameController.makeMove(gs, session, m)
          )
          s <- session.get
        yield assertTrue(s.state.halfmoveClock == 0)
      },
      test(
        "claimDraw(50-move) succeeds when clock accumulates to 100 across real moves"
      ) {
        // Start from a sparse endgame FEN with halfmoveClock = 95, play 5
        // non-repeating knight moves to reach exactly 100.
        val startFen = "4k3/4n3/8/8/8/8/4N3/4K3 w - - 95 50"
        val moves = List("Nc3", "Nc6", "Ne4", "Ne5", "Nc5")
        for
          start <- chess.codec.FenParserRegex.parse(startFen)
          (gs, session) <- withSession
          gameId <- session.get.map(_.gameId)
          _ <- gs.saveState(gameId, start)
          _ <- session.set(SessionState(GameSnapshot.fresh(gameId, start)))
          _ <- ZIO.foreach(moves)(m =>
            GameController.makeMove(gs, session, m)
          )
          afterMoves <- session.get
          _ <- GameController.claimDraw(gs, session)
          afterClaim <- session.get
        yield assertTrue(
          afterMoves.state.halfmoveClock == 100,
          afterMoves.state.status == GameStatus.Playing,
          afterClaim.state.status ==
            GameStatus.Draw(DrawReason.FiftyMoveRule)
        )
      }
    )
  ).provide(appLayer)

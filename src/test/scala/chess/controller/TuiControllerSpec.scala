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

object TuiControllerSpec extends ZIOSpecDefault:

  private val appLayer: ULayer[GameService] =
    InMemoryGameRepository.layer >>> GameServiceLive.layer

  private enum TuiEvent:
    case Input(line: String)
    case ExternalChange
    case Shutdown

  private def withSession =
    for
      gs <- ZIO.service[GameService]
      event <- gs.newGame()
      session <- SubscriptionRef.make(
        SessionState(GameSnapshot(event.gameId, event.initialState, Nil, Nil, event.initialState))
      )
      shutdown <- Promise.make[Nothing, Unit]
    yield (gs, session, shutdown)

  /** Simulates the event race from Main.tuiLoop */
  private def awaitEvent(
      inputQueue: Queue[String],
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit]
  ): UIO[TuiEvent] =
    inputQueue.take
      .map(TuiEvent.Input(_))
      .race(
        session.changes.drop(1).take(1).runHead.as(TuiEvent.ExternalChange)
      )
      .race(
        shutdown.await.as(TuiEvent.Shutdown)
      )

  private def handle(input: String, flipped: Boolean = false) =
    for
      (gs, session, shutdown) <- withSession
      command = TuiController.parseCommand(input)
      result <- TuiController.handleCommand(
        command,
        gs,
        session,
        shutdown,
        flipped
      )
      isDone <- shutdown.isDone
      s <- session.get
    yield (result, isDone, s)

  def spec = suite("TuiController")(
    suite("parseCommand")(
      test("parse quit") {
        assertTrue(
          TuiController.parseCommand("quit") == TuiController.Command.Quit
        )
      },
      test("parse quit with whitespace") {
        assertTrue(
          TuiController.parseCommand("  quit  ") == TuiController.Command.Quit
        )
      },
      test("parse help") {
        assertTrue(
          TuiController.parseCommand("help") == TuiController.Command.Help
        )
      },
      test("parse flip") {
        assertTrue(
          TuiController.parseCommand("flip") == TuiController.Command.Flip
        )
      },
      test("parse undo") {
        assertTrue(
          TuiController.parseCommand("undo") == TuiController.Command.Undo
        )
      },
      test("parse redo") {
        assertTrue(
          TuiController.parseCommand("redo") == TuiController.Command.Redo
        )
      },
      test("parse draw") {
        assertTrue(
          TuiController.parseCommand("draw") == TuiController.Command.Draw
        )
      },
      test("parse move input") {
        assertTrue(
          TuiController.parseCommand("e2 e4") == TuiController.Command.Move(
            "e2 e4"
          )
        )
      },
      test("parse SAN input") {
        assertTrue(
          TuiController.parseCommand("Nf3") == TuiController.Command.Move(
            "Nf3"
          )
        )
      },
      test("parse load command") {
        assertTrue(
          TuiController.parseCommand(
            "load rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
          ) == TuiController.Command.Load(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
          )
        )
      },
      test("parse load command with leading whitespace") {
        assertTrue(
          TuiController.parseCommand(
            "  load 4k3/8/8/8/8/8/8/4K3 w - - 0 1"
          ) == TuiController.Command.Load(
            "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
          )
        )
      },
      test("parse export fen") {
        assertTrue(
          TuiController.parseCommand("export fen") ==
            TuiController.Command.Export(TuiController.ExportFormat.Fen)
        )
      },
      test("parse export pgn") {
        assertTrue(
          TuiController.parseCommand("export pgn") ==
            TuiController.Command.Export(TuiController.ExportFormat.Pgn)
        )
      },
      test("parse export json") {
        assertTrue(
          TuiController.parseCommand("export json") ==
            TuiController.Command.Export(TuiController.ExportFormat.Json)
        )
      },
      test("parse export with unknown format falls through to move") {
        assertTrue(
          TuiController.parseCommand("export xyz") ==
            TuiController.Command.Move("export xyz")
        )
      }
    ),
    suite("handleCommand")(
      test("quit sets shutdown promise and returns Shutdown") {
        for (result, isDone, _) <- handle("quit")
        yield assertTrue(
          result == TuiController.Result.Shutdown,
          isDone
        )
      },
      test("help returns Continue with same flip state") {
        for (result, _, _) <- handle("help", flipped = true)
        yield assertTrue(
          result == TuiController.Result.Continue(true)
        )
      },
      test("flip toggles flipped state") {
        for
          (resultOff, _, _) <- handle("flip", flipped = false)
          (resultOn, _, _) <- handle("flip", flipped = true)
        yield assertTrue(
          resultOff == TuiController.Result.Continue(true),
          resultOn == TuiController.Result.Continue(false)
        )
      },
      test("valid move returns Continue and updates session") {
        for (result, _, s) <- handle("e2 e4")
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.state.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          ),
          s.moves.nonEmpty
        )
      },
      test("invalid move returns Continue with error in session") {
        for (result, _, s) <- handle("e2 e5")
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.error.isDefined
        )
      },
      test("load auto-detects FEN and initializes game") {
        val fen = "4k3/4R3/8/8/8/8/8/4K3 b - - 0 1"
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Load(fen), gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.state.activeColor == Color.Black,
          s.state.board.size == 3,
          s.state.board(Position('e', 7)) == Piece(Color.White, PieceType.Rook),
          s.state.inCheck,
          s.moves.isEmpty,
          s.error.isEmpty
        )
      },
      test("load auto-detects PGN and replays moves") {
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Load("1. e4 e5 2. Nf3 *"),
            gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.moves.length == 3,
          s.state.board(Position('f', 3)) == Piece(Color.White, PieceType.Knight),
          s.error.isEmpty
        )
      },
      test("load auto-detects JSON and initializes game") {
        val json = """{
          "board": {
            "e1": "white king",
            "e8": "black king"
          },
          "activeColor": "white",
          "castlingRights": {
            "whiteKingSide": false,
            "whiteQueenSide": false,
            "blackKingSide": false,
            "blackQueenSide": false
          },
          "enPassantTarget": null,
          "inCheck": false,
          "status": "playing"
        }"""
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Load(json), gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.state.board.size == 2,
          s.moves.isEmpty,
          s.error.isEmpty
        )
      },
      test("load with invalid input sets error") {
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Load("not valid anything"),
            gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.error.isDefined
        )
      },
      test("load resets moves for FEN") {
        for
          (gs, session, shutdown) <- withSession
          _ <- TuiController.handleCommand(
            TuiController.Command.Move("e2 e4"), gs, session, shutdown, false
          )
          beforeLoad <- session.get
          _ <- TuiController.handleCommand(
            TuiController.Command.Load(
              "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            ),
            gs, session, shutdown, false
          )
          afterLoad <- session.get
        yield assertTrue(
          beforeLoad.moves.nonEmpty,
          afterLoad.moves.isEmpty
        )
      },
      test("export fen puts FEN text into session output") {
        for
          (gs, session, shutdown) <- withSession
          _ <- TuiController.handleCommand(
            TuiController.Command.Export(TuiController.ExportFormat.Fen),
            gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          s.output.isDefined,
          s.error.isEmpty,
          s.output.get.contains("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
        )
      },
      test("export pgn puts PGN text into session output") {
        for
          (gs, session, shutdown) <- withSession
          _ <- TuiController.handleCommand(
            TuiController.Command.Move("e2 e4"), gs, session, shutdown, false
          )
          _ <- TuiController.handleCommand(
            TuiController.Command.Export(TuiController.ExportFormat.Pgn),
            gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          s.output.isDefined,
          s.error.isEmpty,
          s.output.get.contains("1. e4"),
          s.output.get.contains("[Event")
        )
      },
      test("export json puts JSON text into session output") {
        for
          (gs, session, shutdown) <- withSession
          _ <- TuiController.handleCommand(
            TuiController.Command.Export(TuiController.ExportFormat.Json),
            gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          s.output.isDefined,
          s.error.isEmpty,
          s.output.get.contains("\"board\""),
          s.output.get.contains("\"activeColor\"")
        )
      },
      test("flip clears previous error from session") {
        for
          (gs, session, shutdown) <- withSession
          _ <- TuiController.handleCommand(
            TuiController.Command.Move("e2 e5"),
            gs, session, shutdown, false
          )
          withError <- session.get
          _ <- TuiController.handleCommand(
            TuiController.Command.Flip,
            gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          withError.error.isDefined,
          s.error.isEmpty
        )
      },
      test("help clears previous error from session") {
        for
          (gs, session, shutdown) <- withSession
          _ <- TuiController.handleCommand(
            TuiController.Command.Move("e2 e5"),
            gs, session, shutdown, false
          )
          withError <- session.get
          _ <- TuiController.handleCommand(
            TuiController.Command.Help,
            gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          withError.error.isDefined,
          s.error.isEmpty
        )
      },
      test("quit does not recurse") {
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Quit,
            gs, session, shutdown, false
          )
          isDone <- shutdown.isDone
        yield assertTrue(
          result == TuiController.Result.Shutdown,
          isDone
        )
      },
      test("undo reverts the last move") {
        for
          (gs, session, shutdown) <- withSession
          _ <- TuiController.handleCommand(
            TuiController.Command.Move("e2 e4"), gs, session, shutdown, false
          )
          result <- TuiController.handleCommand(
            TuiController.Command.Undo, gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.moves.isEmpty,
          s.state == GameState.initial
        )
      },
      test("undo with no moves sets error") {
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Undo, gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.error.isDefined
        )
      },
      test("redo reapplies an undone move") {
        for
          (gs, session, shutdown) <- withSession
          _ <- TuiController.handleCommand(
            TuiController.Command.Move("e2 e4"), gs, session, shutdown, false
          )
          _ <- TuiController.handleCommand(
            TuiController.Command.Undo, gs, session, shutdown, false
          )
          result <- TuiController.handleCommand(
            TuiController.Command.Redo, gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.moves.length == 1,
          s.state.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          )
        )
      },
      test("redo with no redo stack sets error") {
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Redo, gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.error.isDefined
        )
      },
      test("draw claim succeeds when halfmove clock is 100") {
        val drawableState = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          Color.White,
          halfmoveClock = 100
        )
        for
          (gs, session, shutdown) <- withSession
          gameId <- session.get.map(_.gameId)
          _ <- gs.saveState(gameId, drawableState)
          _ <- session.update(st =>
            st.copy(game = st.game.copy(state = drawableState))
          )
          result <- TuiController.handleCommand(
            TuiController.Command.Draw, gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.state.status == GameStatus.Draw(DrawReason.FiftyMoveRule),
          s.error.isEmpty
        )
      },
      test("draw claim fails with error when clock is below 100") {
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Draw, gs, session, shutdown, false
          )
          s <- session.get
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.error.isDefined,
          s.error.get.contains("need 50")
        )
      }
    ),
    suite("TUI-GUI interaction")(
      test("queue-based input survives external session changes") {
        for
          (gs, session, shutdown) <- withSession
          inputQueue <- Queue.unbounded[String]
          _ <- GameController.makeMove(gs, session, "e2 e4")
          _ <- inputQueue.offer("quit")
          event <- inputQueue.take.map(TuiEvent.Input(_))
          result <- TuiController.handleCommand(
            TuiController.parseCommand("quit"),
            gs, session, shutdown, false
          )
          isDone <- shutdown.isDone
        yield assertTrue(
          event == TuiEvent.Input("quit"),
          result == TuiController.Result.Shutdown,
          isDone
        )
      },
      test("queued input is not lost when external change fires") {
        for
          (_, session, shutdown) <- withSession
          inputQueue <- Queue.unbounded[String]
          _ <- inputQueue.offer("e2 e4")
          _ <- session.update(_.copy(error = Some("external")))
          _ <- inputQueue.offer("quit")
          size <- inputQueue.size
          first <- inputQueue.take
          second <- inputQueue.take
        yield assertTrue(
          size == 2,
          first == "e2 e4",
          second == "quit"
        )
      },
      test("shutdown event wins the race over pending queue take") {
        for
          (_, session, shutdown) <- withSession
          inputQueue <- Queue.unbounded[String]
          _ <- shutdown.succeed(())
          event <- awaitEvent(inputQueue, session, shutdown)
        yield assertTrue(event == TuiEvent.Shutdown)
      }
    )
  ).provide(appLayer)

package chess.controller

import chess.model.SessionState
import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
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
        SessionState(event.gameId, event.initialState, Nil, None)
      )
      shutdown <- Promise.make[Nothing, Unit]
    yield (gs, session, shutdown)

  /** Simulates the event race from Main.tuiLoop */
  private def awaitEvent(
      inputQueue: Queue[String],
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit]
  ): UIO[TuiEvent] =
    inputQueue.take.map(TuiEvent.Input(_))
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
        command, gs, session, shutdown, flipped
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
          s.moveLog.nonEmpty
        )
      },
      test("invalid move returns Continue with error in session") {
        for (result, _, s) <- handle("e2 e5")
        yield assertTrue(
          result == TuiController.Result.Continue(false),
          s.error.isDefined
        )
      },
      test("quit does not recurse") {
        for
          (gs, session, shutdown) <- withSession
          result <- TuiController.handleCommand(
            TuiController.Command.Quit, gs, session, shutdown, false
          )
          isDone <- shutdown.isDone
        yield assertTrue(
          result == TuiController.Result.Shutdown,
          isDone
        )
      }
    ),
    suite("TUI-GUI interaction")(
      test("queue-based input survives external session changes") {
        // Core regression: with readLine-per-iteration, input was lost
        // after an ExternalChange. With a queue, it's preserved.
        for
          (gs, session, shutdown) <- withSession
          inputQueue <- Queue.unbounded[String]
          // 1. GUI makes a move, changing the session
          _ <- GameController.makeMove(gs, session, "e2 e4")
          // 2. User types quit after the session change
          _ <- inputQueue.offer("quit")
          // 3. Queue still delivers the input
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
          // Input is queued before and after a session change
          _ <- inputQueue.offer("e2 e4")
          _ <- session.update(_.copy(error = Some("external")))
          _ <- inputQueue.offer("quit")
          // Both items are still in the queue
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

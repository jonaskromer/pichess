package chess

import chess.controller.{GameController, TuiController, WebController}
import chess.model.{GameSnapshot, SessionState}
import chess.repository.InMemoryGameRepository
import chess.service.GameService
import chess.notation.SanSerializer
import chess.view.{BoardView, HelpView, MoveLogView}
import zio.*
import zio.Console.*
import zio.http.*
import zio.stream.SubscriptionRef

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers

  private enum TuiEvent:
    case Input(line: String)
    case ExternalChange
    case Shutdown

  def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      args <- ZIOAppArgs.getArgs
      headless = args.contains("--headless")
      _ <- app(headless).provide(
        GameService.layer,
        InMemoryGameRepository.layer
      )
    yield ()

  private def app(headless: Boolean): ZIO[GameService, Throwable, Unit] =
    for
      gs <- ZIO.service[GameService]
      event <- gs.newGame()
      session <- SubscriptionRef.make(
        SessionState(GameSnapshot(event.gameId, event.initialState))
      )
      shutdown <- Promise.make[Nothing, Unit]
      inputQueue <- Queue.unbounded[String]
      _ <- readLine.flatMap(inputQueue.offer).forever.forkDaemon
      serverFiber <-
        if headless then ZIO.none
        else startGui(gs, session, shutdown).map(Some(_))
      _ <- tuiLoop(gs, session, shutdown, inputQueue, flipped = false)
      _ <- shutdown.await
      _ <- printLine("Goodbye!")
      _ <- ZIO.foreachDiscard(serverFiber)(f =>
        ZIO.sleep(500.millis) *> f.interrupt
      )
    yield ()

  private def startGui(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit]
  ): Task[Fiber.Runtime[Throwable, Nothing]] =
    (for
      fiber <- Server
        .serve(WebController.routes(gs, session, shutdown))
        .fork
      _ <- openBrowser.delay(1.second).forkDaemon
    yield fiber).provide(Server.defaultWithPort(8090))

  private def tuiLoop(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
      inputQueue: Queue[String],
      flipped: Boolean
  ): ZIO[Any, Throwable, Unit] =
    for
      s <- session.get
      moveLog <- SanSerializer
        .deriveMoveLog(s.initialState, s.history)
        .orDie
      _ <- printLine(BoardView.render(s.state, flipped))
      _ <- ZIO.when(moveLog.nonEmpty)(
        printLine(MoveLogView.render(moveLog))
      )
      _ <- ZIO.when(s.output.isDefined)(
        printLine(s.output.get)
      )
      _ <- ZIO.when(s.error.isDefined)(
        printLine(s"Error: ${s.error.get}")
      )
      _ <- printLine(
        s"${s.state.activeColor}'s turn — enter a move (e.g. e2 e4), 'help', 'flip', or 'quit':"
      )
      event <- inputQueue.take
        .map(TuiEvent.Input(_))
        .race(
          session.changes.drop(1).take(1).runHead.as(TuiEvent.ExternalChange)
        )
        .race(
          shutdown.await.as(TuiEvent.Shutdown)
        )
      _ <- event match
        case TuiEvent.Shutdown => ZIO.unit
        case TuiEvent.ExternalChange =>
          tuiLoop(gs, session, shutdown, inputQueue, flipped)
        case TuiEvent.Input(input) =>
          val command = TuiController.parseCommand(input)
          for
            _ <- ZIO.when(command == TuiController.Command.Help)(
              printLine(HelpView.render)
            )
            result <- TuiController.handleCommand(
              command,
              gs,
              session,
              shutdown,
              flipped
            )
            _ <- result match
              case TuiController.Result.Shutdown => ZIO.unit
              case TuiController.Result.Continue(f) =>
                tuiLoop(gs, session, shutdown, inputQueue, f)
          yield ()
    yield ()

  private def openBrowser: Task[Unit] =
    for
      os <- zio.System
        .property("os.name")
        .orDie
        .map(_.getOrElse("").toLowerCase)
      cmd =
        if os.contains("mac") then List("open", "http://localhost:8090")
        else if os.contains("win") then
          List("cmd", "/c", "start", "http://localhost:8090")
        else List("xdg-open", "http://localhost:8090")
      _ <- zio.process.Command(cmd.head, cmd.tail*).run.orDie
    yield ()

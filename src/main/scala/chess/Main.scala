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
    ZIOAppArgs.getArgs.flatMap(args =>
      app(args.contains("--headless")).provide(
        GameService.layer,
        InMemoryGameRepository.layer
      )
    )

  private[chess] def app(
      headless: Boolean,
      port: Int = 8090,
      onReady: Int => Task[Unit] = port => openBrowser(port).delay(1.second)
  ): ZIO[GameService, Throwable, Unit] =
    for
      gs <- ZIO.service[GameService]
      event <- gs.newGame()
      session <- SubscriptionRef.make(
        SessionState(GameSnapshot(event.gameId, event.initialState))
      )
      shutdown <- Promise.make[Nothing, Unit]
      done <- runWith(session, shutdown, headless, port, onReady)
    yield done

  private[chess] def runWith(
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
      headless: Boolean,
      port: Int,
      onReady: Int => Task[Unit]
  ): ZIO[GameService, Throwable, Unit] =
    ZIO.scoped {
      for
        gs <- ZIO.service[GameService]
        inputQueue <- Queue.unbounded[String]
        _ <- readLine.flatMap(inputQueue.offer).forever.forkDaemon
        _ <- ZIO.unless(headless)(
          startGui(gs, session, shutdown, port, onReady)
        )
        _ <- tuiLoop(gs, session, shutdown, inputQueue, flipped = false)
        _ <- shutdown.await
        done <- printLine("Goodbye!")
      yield done
    }

  private[chess] def startGui(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
      port: Int,
      onReady: Int => Task[Unit]
  ): ZIO[Scope, Throwable, Int] =
    for
      serverEnv <- Server.defaultWithPort(port).build
      boundPort <- Server
        .install(WebController.routes(gs, session, shutdown))
        .provideEnvironment(serverEnv)
      _ <- onReady(boundPort).forkDaemon
    yield boundPort

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
      done <- event match
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
            next <- result match
              case TuiController.Result.Shutdown => ZIO.unit
              case TuiController.Result.Continue(f) =>
                tuiLoop(gs, session, shutdown, inputQueue, f)
          yield next
    yield done

  private[chess] def browserCommandFor(
      osName: String,
      port: Int
  ): List[String] =
    val os = osName.toLowerCase
    val url = s"http://localhost:$port"
    if os.contains("mac") then List("open", url)
    else if os.contains("win") then List("cmd", "/c", "start", url)
    else List("xdg-open", url)

  private[chess] def openBrowser(
      port: Int,
      runner: List[String] => Task[Unit] = runCommand
  ): Task[Unit] =
    for
      os <- zio.System
        .property("os.name")
        .orDie
        .map(_.getOrElse(""))
      done <- runner(browserCommandFor(os, port))
    yield done

  private[chess] val runCommand: List[String] => Task[Unit] =
    cmd => zio.process.Command(cmd.head, cmd.tail*).run.unit

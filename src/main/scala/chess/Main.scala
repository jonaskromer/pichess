package chess

import chess.controller.WebController
import chess.model.SessionState
import chess.notation.SanSerializer
import chess.repository.InMemoryGameRepository
import chess.service.GameService
import chess.view.{BoardView, HelpView, MoveLogView}
import zio.*
import zio.Console.*
import zio.http.*
import zio.stream.SubscriptionRef

object Main extends ZIOAppDefault:

  private enum TuiEvent:
    case Input(line: String)
    case ExternalChange
    case Shutdown

  def run: ZIO[Any, Throwable, Unit] =
    (for
      gs <- ZIO.service[GameService]
      event <- gs.newGame()
      session <- SubscriptionRef.make(
        SessionState(event.gameId, event.initialState, Nil, None)
      )
      shutdown <- Promise.make[Nothing, Unit]
      serverFiber <- Server
        .serve(WebController.routes(gs, session, shutdown))
        .fork
      _ <- openBrowser.delay(1.second).forkDaemon
      _ <- tuiLoop(gs, session, shutdown, flipped = false)
      _ <- shutdown.await
      _ <- printLine("Goodbye!")
      _ <- ZIO.sleep(500.millis)
      _ <- serverFiber.interrupt
    yield ()).provide(
      GameService.layer,
      InMemoryGameRepository.layer,
      Server.defaultWithPort(8090)
    )

  private def tuiLoop(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
      flipped: Boolean
  ): ZIO[Any, Throwable, Unit] =
    for
      s <- session.get
      _ <- printLine(BoardView.render(s.state, flipped))
      _ <- ZIO.when(s.moveLog.nonEmpty)(
        printLine(MoveLogView.render(s.moveLog))
      )
      _ <- printLine(
        s"${s.state.activeColor}'s turn — enter a move (e.g. e2 e4), 'help', 'flip', or 'quit':"
      )
      event <- readLine.map(TuiEvent.Input(_))
        .race(
          session.changes.drop(1).take(1).runHead.as(TuiEvent.ExternalChange)
        )
        .race(
          shutdown.await.as(TuiEvent.Shutdown)
        )
      _ <- event match
        case TuiEvent.Shutdown => ZIO.unit
        case TuiEvent.ExternalChange =>
          tuiLoop(gs, session, shutdown, flipped)
        case TuiEvent.Input(input) =>
          input.trim match
            case "quit" => shutdown.succeed(())
            case "help" =>
              printLine(HelpView.render) *> tuiLoop(
                gs,
                session,
                shutdown,
                flipped
              )
            case "flip" => tuiLoop(gs, session, shutdown, !flipped)
            case raw =>
              session.get.flatMap { s =>
                val color = s.state.activeColor
                gs.makeMove(s.gameId, raw)
                  .foldZIO(
                    err =>
                      printLine(s"Error: ${err.getMessage}") *> tuiLoop(
                        gs,
                        session,
                        shutdown,
                        flipped
                      ),
                    (newState, event) =>
                      val san = SanSerializer.toSan(event.move, s.state)
                      session.update(st =>
                        st.copy(
                          state = newState,
                          moveLog = st.moveLog :+ (color, san),
                          error = None
                        )
                      ) *> tuiLoop(gs, session, shutdown, flipped)
                  )
              }
    yield ()

  private def openBrowser: Task[Unit] = ZIO.attempt {
    val os = java.lang.System.getProperty("os.name").toLowerCase
    val cmd =
      if os.contains("mac") then Array("open", "http://localhost:8090")
      else if os.contains("win") then
        Array("cmd", "/c", "start", "http://localhost:8090")
      else Array("xdg-open", "http://localhost:8090")
    java.lang.Runtime.getRuntime.exec(cmd)
    ()
  }

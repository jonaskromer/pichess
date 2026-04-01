package chess

import chess.controller.WebController
import chess.notation.SanSerializer
import chess.model.board.GameState
import chess.model.GameId
import chess.model.piece.Color
import chess.repository.InMemoryGameRepository
import chess.service.GameService
import chess.view.{BoardView, HelpView, MoveLogView}
import zio.*
import zio.Console.*
import zio.http.*

object Main extends ZIOAppDefault:

  def run: ZIO[Any, Throwable, Unit] =
    (for
      gs <- ZIO.service[GameService]
      tuiEvent <- gs.newGame()
      guiEvent <- gs.newGame()
      session <- Ref.make(
        WebController.SessionState(
          guiEvent.gameId,
          guiEvent.initialState,
          Nil,
          None
        )
      )
      _ <- Server
        .serve(WebController.routes(gs, session))
        .zipPar(openBrowser.delay(1.second))
        .zipPar(
          tuiLoop(
            gs,
            tuiEvent.gameId,
            tuiEvent.initialState,
            flipped = false,
            moveLog = Nil
          )
        )
    yield ()).provide(
      GameService.layer,
      InMemoryGameRepository.layer,
      Server.defaultWithPort(8090)
    )

  private def tuiLoop(
      gs: GameService,
      id: GameId,
      state: GameState,
      flipped: Boolean,
      moveLog: List[(Color, String)]
  ): ZIO[Any, Throwable, Unit] =
    for
      _ <- printLine(BoardView.render(state, flipped))
      _ <- ZIO.when(moveLog.nonEmpty)(printLine(MoveLogView.render(moveLog)))
      _ <- printLine(
        s"${state.activeColor}'s turn — enter a move (e.g. e2 e4), 'help', 'flip', or 'quit':"
      )
      input <- readLine
      _ <- input.trim match
        case "quit" => printLine("Goodbye!")
        case "help" =>
          printLine(HelpView.render) *> tuiLoop(
            gs,
            id,
            state,
            flipped,
            moveLog
          )
        case "flip" => tuiLoop(gs, id, state, !flipped, moveLog)
        case raw =>
          val color = state.activeColor
          gs.makeMove(id, raw)
            .foldZIO(
              err =>
                printLine(s"Error: ${err.getMessage}") *> tuiLoop(
                  gs,
                  id,
                  state,
                  flipped,
                  moveLog
                ),
              (newState, event) =>
                val san = SanSerializer.toSan(event.move, state)
                tuiLoop(gs, id, newState, flipped, moveLog :+ (color, san))
            )
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

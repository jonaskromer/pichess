package chess

import chess.notation.SanSerializer
import chess.model.GameId
import chess.model.board.GameState
import chess.repository.InMemoryGameRepository
import chess.service.GameService
import chess.view.{BoardView, HelpView, MoveLogView}
import chess.model.piece.Color
import zio.*
import zio.Console.*

object Main extends ZIOAppDefault:
  def run: ZIO[Any, Throwable, Unit] =
    app.provide(
      GameService.layer,
      InMemoryGameRepository.layer
    )

  private val app: ZIO[GameService, Throwable, Unit] =
    for
      event <- GameService.newGame()
      _ <- loop(event.gameId, event.initialState, flipped = false, moveLog = Nil)
    yield ()

  private def loop(
      id: GameId,
      state: GameState,
      flipped: Boolean,
      moveLog: List[(Color, String)]
  ): ZIO[GameService, Throwable, Unit] =
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
          printLine(HelpView.render) *> loop(id, state, flipped, moveLog)
        case "flip" => loop(id, state, !flipped, moveLog)
        case raw =>
          val color = state.activeColor
          GameService
            .makeMove(id, raw)
            .foldZIO(
              err =>
                printLine(s"Error: ${err.getMessage}") *> loop(
                  id,
                  state,
                  flipped,
                  moveLog
                ),
              (newState, event) =>
                val san = SanSerializer.toSan(event.move, state)
                loop(id, newState, flipped, moveLog :+ (color, san))
            )
    yield ()

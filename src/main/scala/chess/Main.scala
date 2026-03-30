package chess

import chess.model.GameId
import chess.model.board.GameState
import chess.repository.InMemoryGameRepository
import chess.service.GameService
import chess.view.BoardView
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
      _ <- loop(event.gameId, event.initialState)
    yield ()

  private def loop(id: GameId, state: GameState): ZIO[GameService, Throwable, Unit] =
    for
      _ <- printLine(BoardView.render(state))
      _ <- printLine(s"${state.activeColor}'s turn — enter a move (e.g. e2 e4) or 'quit':")
      input <- readLine
      _ <- input.trim match
        case "quit" => printLine("Goodbye!")
        case raw =>
          GameService
            .makeMove(id, raw)
            .foldZIO(
              err => printLine(s"Error: ${err.getMessage}") *> loop(id, state),
              (newState, _) => loop(id, newState)
            )
    yield ()

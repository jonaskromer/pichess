package chess.model

import chess.model.board.GameState
import chess.model.piece.Color

case class GameSnapshot(
    gameId: GameId,
    state: GameState,
    moveLog: List[(Color, String)]
)

case class SessionState(
    game: GameSnapshot,
    error: Option[String] = None,
    output: Option[String] = None
):
  export game.{gameId, state, moveLog}

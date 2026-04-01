package chess.model

import chess.model.board.GameState
import chess.model.piece.Color

case class SessionState(
    gameId: GameId,
    state: GameState,
    moveLog: List[(Color, String)],
    error: Option[String]
)

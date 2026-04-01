package chess.model.board

import chess.model.piece.Color

case class GameState(
    board: Board,
    activeColor: Color,
    enPassantTarget: Option[Position] = None
)

object GameState:
  val initial: GameState = GameState(Board.initial, Color.White)

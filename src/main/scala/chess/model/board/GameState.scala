package chess.model.board

import chess.model.piece.Color

case class GameState(
    board: Board,
    activeColor: Color,
    enPassantTarget: Option[Position] = None,
    inCheck: Boolean = false,
    castlingRights: CastlingRights = CastlingRights(),
    status: GameStatus = GameStatus.Playing
)

object GameState:
  val initial: GameState = GameState(Board.initial, Color.White)

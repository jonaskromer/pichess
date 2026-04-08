package chess.model

import chess.model.board.{GameState, Move}

case class GameSnapshot(
    gameId: GameId,
    initialState: GameState,
    moves: List[Move],
    redoStack: List[Move],
    state: GameState
)

case class SessionState(
    game: GameSnapshot,
    error: Option[String] = None,
    output: Option[String] = None
):
  export game.{gameId, initialState, moves, redoStack, state}

package chess.model

import chess.model.board.{GameState, Move}

case class GameSnapshot(
    gameId: GameId,
    initialState: GameState,
    history: List[(Move, GameState)] = Nil,
    redoStack: List[(Move, GameState)] = Nil
):
  def state: GameState = history.headOption.map(_._2).getOrElse(initialState)
  def moves: List[Move] = history.reverse.map(_._1)

case class SessionState(
    game: GameSnapshot,
    error: Option[String] = None,
    output: Option[String] = None
):
  export game.{gameId, initialState, history, redoStack, state, moves}

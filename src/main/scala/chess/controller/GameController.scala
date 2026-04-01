package chess.controller

import chess.model.board.GameState
import chess.model.rules.Game

object GameController:
  def handleInput(
      state: GameState,
      input: String
  ): Option[Either[chess.model.GameError, GameState]] =
    input match
      case "quit" => None
      case in =>
        Some(
          for
            move <- MoveParser.parse(in, state)
            newState <- Game.applyMove(state, move)
          yield newState
        )

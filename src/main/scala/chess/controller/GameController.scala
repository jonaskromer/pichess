package chess.controller

import chess.model.board.GameState
import chess.model.rules.Game

object GameController:
  def handleInput(state: GameState, input: String): Option[Either[String, GameState]] =
    input match
      case "quit" => None
      case in     => Some(MoveParser.parse(in).flatMap(move => Game.applyMove(state, move)))

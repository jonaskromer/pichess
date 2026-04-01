package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move}

trait NotationResolver:
  def parse(input: String, state: GameState): Option[Either[GameError, Move]]

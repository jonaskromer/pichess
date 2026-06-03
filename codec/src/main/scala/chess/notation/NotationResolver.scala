package chess.notation

import zio.*

import chess.model.GameError
import chess.model.board.{GameState, Move}

trait NotationResolver:
  def parse(input: String, state: GameState): IO[GameError, Option[Move]]

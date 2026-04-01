package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move}
import zio.*

trait NotationResolver:
  def parse(input: String, state: GameState): IO[GameError, Option[Move]]

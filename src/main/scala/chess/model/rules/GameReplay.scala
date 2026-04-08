package chess.model.rules

import chess.model.GameError
import chess.model.board.{GameState, Move}
import zio.*

object GameReplay:

  def replay(
      initial: GameState,
      moves: List[Move]
  ): IO[GameError, GameState] =
    ZIO.foldLeft(moves)(initial) { (state, move) =>
      Game.applyMove(state, move)
    }

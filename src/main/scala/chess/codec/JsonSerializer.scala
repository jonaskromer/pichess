package chess.codec

import chess.codec.JsonCodec.given
import chess.model.board.GameState
import zio.json.*

object JsonSerializer:

  def serialize(state: GameState): String =
    state.toJsonPretty

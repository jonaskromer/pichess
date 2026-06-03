package chess.codec

import zio.json.*

import chess.codec.JsonCodec.given
import chess.model.board.GameState

object JsonSerializer:

  def serialize(state: GameState): String =
    state.toJsonPretty

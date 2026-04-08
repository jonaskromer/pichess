package chess.codec

import chess.codec.JsonCodec.given
import chess.model.board.GameState
import zio.json.*

object JsonParser:

  def parse(input: String): Either[String, GameState] =
    input.fromJson[GameState]

package chess.codec

import chess.codec.JsonCodec.given
import chess.model.GameError
import chess.model.board.GameState
import zio.*
import zio.json.*

object JsonParser:

  def parse(input: String): IO[GameError, GameState] =
    ZIO
      .fromEither(input.fromJson[GameState])
      .mapError(GameError.ParseError(_))

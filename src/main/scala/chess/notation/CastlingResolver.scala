package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move}
import zio.*

object CastlingResolver extends NotationResolver:

  // O-O or O-O-O (with optional check/mate suffix)
  private val pattern = """^O-O(-O)?[+#]?$""".r

  def parse(input: String, state: GameState): IO[GameError, Option[Move]] =
    input match
      case pattern(suffix) =>
        val side = if suffix == null then "Kingside" else "Queenside"
        ZIO.fail(
          GameError.InvalidMove(s"$side castling is not yet implemented")
        )
      case _ => ZIO.succeed(None)

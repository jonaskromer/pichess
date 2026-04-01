package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move}

object CastlingResolver extends NotationResolver:

  // O-O or O-O-O (with optional check/mate suffix)
  private val pattern = """^O-O(-O)?[+#]?$""".r

  def parse(input: String, state: GameState): Option[Either[GameError, Move]] =
    input match
      case pattern(suffix) =>
        val side = if suffix == null then "Kingside" else "Queenside"
        Some(
          Left(GameError.InvalidMove(s"$side castling is not yet implemented"))
        )
      case _ => None

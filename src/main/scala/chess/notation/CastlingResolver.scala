package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.Color
import zio.*

object CastlingResolver extends NotationResolver:

  // O-O or O-O-O (with optional check/mate suffix)
  private val pattern = """^O-O(-O)?[+#]?$""".r

  def parse(input: String, state: GameState): IO[GameError, Option[Move]] =
    input match
      case pattern(suffix) =>
        val kingSide = suffix == null
        val rank = if state.activeColor == Color.White then 1 else 8
        val kingFrom = Position('e', rank)
        val kingTo =
          if kingSide then Position('g', rank) else Position('c', rank)
        ZIO.succeed(Some(Move(kingFrom, kingTo)))
      case _ => ZIO.succeed(None)

package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.PieceType
import zio.*

object CoordinateResolver extends NotationResolver:

  // e2 e4 | e2e4 | e2-e4 | e7 e8=Q
  private val pattern =
    """^([a-h][1-8])[\s-]*([a-h][1-8])(?:=([NBRQ]))?$""".r

  private val pieceLetters: Map[String, PieceType] = Map(
    "N" -> PieceType.Knight,
    "B" -> PieceType.Bishop,
    "R" -> PieceType.Rook,
    "Q" -> PieceType.Queen
  )

  def parse(input: String, state: GameState): IO[GameError, Option[Move]] =
    ZIO.succeed {
      input match
        case pattern(f, t, promo) =>
          val promotion = Option(promo).flatMap(pieceLetters.get)
          Some(Move(pos(f), pos(t), promotion))
        case _ => None
    }

  private def pos(s: String): Position = Position(s.head, s(1) - '0')

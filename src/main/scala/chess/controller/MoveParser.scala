package chess.controller

import chess.model.GameError
import chess.model.board.Position
import chess.model.piece.PieceType

object MoveParser:
  private val hint = "Type 'help' for notation guide"

  // Coordinate: e2 e4 | e2e4 | e2-e4
  private val coordPattern =
    """^([a-h][1-8])[\s-]*([a-h][1-8])$""".r

  // Castling: O-O or O-O-O (with optional check/mate suffix)
  private val castlingPattern =
    """^O-O(-O)?[+#]?$""".r

  // Piece move: [NBRQK][disambig-file?][disambig-rank?][x?][dest][=promo?][+#?]
  private val piecePattern =
    """^([NBRQK])([a-h]?)([1-8]?)(x?)([a-h][1-8])(?:=[NBRQK])?[+#]?$""".r

  // Pawn capture: exd5 | exd5=Q | exd5+
  private val pawnCapPattern =
    """^([a-h])x([a-h][1-8])(?:=[NBRQK])?[+#]?$""".r

  // Pawn push: e4 | e4+ | e4#
  private val pawnPushPattern =
    """^([a-h][1-8])[+#]?$""".r

  private val pieceLetters: Map[String, PieceType] = Map(
    "N" -> PieceType.Knight,
    "B" -> PieceType.Bishop,
    "R" -> PieceType.Rook,
    "Q" -> PieceType.Queen,
    "K" -> PieceType.King
  )

  def parse(input: String): Either[GameError, ParsedMove] =
    input.trim match
      case coordPattern(f, t) =>
        Right(ParsedMove.Coordinate(pos(f), pos(t)))
      case castlingPattern(suffix) =>
        Right(ParsedMove.Castling(suffix == null))
      case piecePattern(p, file, rank, _, dest) =>
        Right(
          ParsedMove.San(
            pieceLetters(p),
            pos(dest),
            Option(file).filter(_.nonEmpty).map(_.head),
            Option(rank).filter(_.nonEmpty).map(_.head - '0')
          )
        )
      case pawnCapPattern(fromFile, dest) =>
        Right(
          ParsedMove.San(PieceType.Pawn, pos(dest), Some(fromFile.head), None)
        )
      case pawnPushPattern(dest) =>
        Right(ParsedMove.San(PieceType.Pawn, pos(dest), None, None))
      case _ =>
        Left(GameError.ParseError(s"Invalid move. $hint"))

  private def pos(s: String): Position = Position(s.head, s(1) - '0')

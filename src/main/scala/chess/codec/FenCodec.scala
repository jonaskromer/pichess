package chess.codec

import chess.model.board.{Board, CastlingRights, Position}
import chess.model.piece.{Color, Piece, PieceType}

/** Co-located encode/decode pairs for each FEN field.
  *
  * Mirrors the pattern used by [[JsonCodec]]: every domain type that appears in
  * the wire format has its encode and decode logic side-by-side, so the mapping
  * is defined once and round-trip consistency is obvious by inspection.
  *
  * Both [[FenBuilder]] (decode) and [[FenSerializer]] (encode) delegate here.
  */
object FenCodec:

  // ─── Piece ↔ Char ──────────────────────────────────────────────────────────
  //
  // FEN convention: uppercase = White, lowercase = Black.
  // Only the PieceType → letter mapping is listed; color is derived from case.

  private val typeToChar: Map[PieceType, Char] = Map(
    PieceType.King -> 'K',
    PieceType.Queen -> 'Q',
    PieceType.Rook -> 'R',
    PieceType.Bishop -> 'B',
    PieceType.Knight -> 'N',
    PieceType.Pawn -> 'P'
  )
  private val charToType: Map[Char, PieceType] = typeToChar.map(_.swap)

  def pieceToChar(piece: Piece): Char =
    val c = typeToChar(piece.pieceType)
    if piece.color == Color.White then c else c.toLower

  def charToPiece(ch: Char): Option[Piece] =
    val color = if ch.isUpper then Color.White else Color.Black
    charToType.get(ch.toUpper).map(Piece(color, _))

  // ─── Color ─────────────────────────────────────────────────────────────────

  private val colorToFen: Map[Color, String] =
    Map(Color.White -> "w", Color.Black -> "b")
  private val fenToColor: Map[String, Color] =
    colorToFen.map(_.swap)

  def encodeColor(c: Color): String = colorToFen(c)

  def decodeColor(s: String): Either[String, Color] =
    fenToColor
      .get(s)
      .toRight(s"Invalid active color '$s' (expected 'w' or 'b')")

  // ─── CastlingRights ────────────────────────────────────────────────────────

  private val castlingFlags: List[(CastlingRights => Boolean, Char)] = List(
    (_.whiteKingSide, 'K'),
    (_.whiteQueenSide, 'Q'),
    (_.blackKingSide, 'k'),
    (_.blackQueenSide, 'q')
  )

  def encodeCastling(rights: CastlingRights): String =
    val s = castlingFlags.collect { case (f, c) if f(rights) => c }.mkString
    if s.isEmpty then "-" else s

  def decodeCastling(s: String): Either[String, CastlingRights] =
    if s == "-" then Right(CastlingRights(false, false, false, false))
    else if s.distinct.length != s.length then
      Left(s"Duplicate castling character(s) in '$s'")
    else
      Right(
        CastlingRights(
          whiteKingSide = s.contains('K'),
          whiteQueenSide = s.contains('Q'),
          blackKingSide = s.contains('k'),
          blackQueenSide = s.contains('q')
        )
      )

  // ─── En passant ────────────────────────────────────────────────────────────

  def encodeEnPassant(ep: Option[Position]): String =
    ep.fold("-")(_.toString)

  private val squarePattern = """^([a-h])([1-8])$""".r

  def decodeEnPassant(s: String): Either[String, Option[Position]] =
    if s == "-" then Right(None)
    else
      s match
        case squarePattern(col, row) =>
          Right(Some(Position(col.head, row.toInt)))
        case _ =>
          Left(s"Invalid en passant target square '$s'")

  // ─── Board placement ───────────────────────────────────────────────────────

  def encodeBoard(board: Board): String =
    (8 to 1 by -1)
      .map { row =>
        val (out, empty) = ('a' to 'h')
          .map(col => board.get(Position(col, row)))
          .foldLeft(("", 0)) {
            case ((out, empty), None) => (out, empty + 1)
            case ((out, empty), Some(piece)) =>
              (flushEmpty(out, empty) + pieceToChar(piece), 0)
          }
        flushEmpty(out, empty)
      }
      .mkString("/")

  private def flushEmpty(out: String, empty: Int): String =
    if empty > 0 then out + empty.toString else out

  def decodeBoard(placement: String): Either[String, Board] =
    val ranks = placement.split('/')
    if ranks.length != 8 then
      Left(s"Piece placement must have 8 ranks, got ${ranks.length}")
    else
      (8 to 1 by -1).toList
        .zip(ranks.toList)
        .foldLeft[Either[String, Board]](Right(Map.empty)) {
          case (acc, (row, rank)) =>
            for
              board <- acc
              rankPart <- decodeRank(rank, row)
            yield board ++ rankPart
        }

  private def decodeRank(
      rank: String,
      row: Int
  ): Either[String, Map[Position, Piece]] =
    rank
      .foldLeft[Either[String, (Int, Map[Position, Piece])]](
        Right((0, Map.empty))
      ) {
        case (Left(e), _) => Left(e)
        case (Right((col, board)), ch) if ch.isDigit =>
          Right((col + ch.asDigit, board))
        case (Right((col, board)), ch) =>
          charToPiece(ch)
            .map { piece =>
              (col + 1, board + (Position(('a' + col).toChar, row) -> piece))
            }
            .toRight(s"Invalid piece character '$ch' on rank $row")
      }
      .flatMap { case (col, board) =>
        Either.cond(
          col == 8,
          board,
          s"Rank $row must describe 8 squares, got $col"
        )
      }

  // ─── Integer fields ────────────────────────────────────────────────────────

  def decodeNonNegativeInt(s: String, field: String): Either[String, Int] =
    s.toIntOption.toRight(s"Invalid $field '$s'")

  def decodePositiveInt(s: String, field: String): Either[String, Int] =
    s.toIntOption.filter(_ >= 1).toRight(s"Invalid $field '$s'")

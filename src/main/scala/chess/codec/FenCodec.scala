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

  val pieceToChar: Map[Piece, Char] = Map(
    Piece(Color.White, PieceType.King) -> 'K',
    Piece(Color.White, PieceType.Queen) -> 'Q',
    Piece(Color.White, PieceType.Rook) -> 'R',
    Piece(Color.White, PieceType.Bishop) -> 'B',
    Piece(Color.White, PieceType.Knight) -> 'N',
    Piece(Color.White, PieceType.Pawn) -> 'P',
    Piece(Color.Black, PieceType.King) -> 'k',
    Piece(Color.Black, PieceType.Queen) -> 'q',
    Piece(Color.Black, PieceType.Rook) -> 'r',
    Piece(Color.Black, PieceType.Bishop) -> 'b',
    Piece(Color.Black, PieceType.Knight) -> 'n',
    Piece(Color.Black, PieceType.Pawn) -> 'p'
  )

  val charToPiece: Map[Char, Piece] = pieceToChar.map(_.swap)

  // ─── Color ─────────────────────────────────────────────────────────────────

  def encodeColor(c: Color): String =
    if c == Color.White then "w" else "b"

  def decodeColor(s: String): Either[String, Color] = s match
    case "w" => Right(Color.White)
    case "b" => Right(Color.Black)
    case _   => Left(s"Invalid active color '$s' (expected 'w' or 'b')")

  // ─── CastlingRights ────────────────────────────────────────────────────────

  def encodeCastling(rights: CastlingRights): String =
    val sb = StringBuilder()
    if rights.whiteKingSide then sb.append('K')
    if rights.whiteQueenSide then sb.append('Q')
    if rights.blackKingSide then sb.append('k')
    if rights.blackQueenSide then sb.append('q')
    if sb.isEmpty then "-" else sb.toString

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
    ep.map(_.toString).getOrElse("-")

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
        val rank = ('a' to 'h').toList.map(col => board.get(Position(col, row)))
        encodeRank(rank)
      }
      .mkString("/")

  private def encodeRank(squares: List[Option[Piece]]): String =
    val (acc, trailingEmpty) = squares.foldLeft(("", 0)) {
      case ((out, empty), None) => (out, empty + 1)
      case ((out, empty), Some(piece)) =>
        val flushed = if empty > 0 then out + empty.toString else out
        (flushed + pieceToChar(piece), 0)
    }
    if trailingEmpty > 0 then acc + trailingEmpty.toString else acc

  def decodeBoard(placement: String): Either[String, Board] =
    val ranks = placement.split('/')
    if ranks.length != 8 then
      Left(s"Piece placement must have 8 ranks, got ${ranks.length}")
    else
      val rows = (8 to 1 by -1).toList.zip(ranks.toList)
      rows
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
    val initial: Either[String, (Int, Map[Position, Piece])] =
      Right((0, Map.empty))
    val folded = rank.foldLeft(initial) {
      case (acc @ Left(_), _) => acc
      case (Right((col, board)), ch) =>
        if ch.isDigit then Right((col + ch.asDigit, board))
        else
          charToPiece.get(ch) match
            case Some(piece) =>
              val pos = Position(('a' + col).toChar, row)
              Right((col + 1, board + (pos -> piece)))
            case None =>
              Left(s"Invalid piece character '$ch' on rank $row")
    }
    folded.flatMap { case (col, board) =>
      if col != 8 then Left(s"Rank $row must describe 8 squares, got $col")
      else Right(board)
    }

  // ─── Integer fields ────────────────────────────────────────────────────────

  def decodeNonNegativeInt(
      s: String,
      field: String
  ): Either[String, Int] =
    s.toIntOption.toRight(s"Invalid $field '$s'")

  def decodePositiveInt(
      s: String,
      field: String
  ): Either[String, Int] =
    s.toIntOption.filter(_ >= 1).toRight(s"Invalid $field '$s'")

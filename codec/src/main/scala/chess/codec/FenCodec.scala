package chess.codec

import chess.model.GameError
import chess.model.board.{Board, CastlingRights, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*

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

  def decodeColor(s: String): IO[GameError, Color] =
    ZIO
      .fromOption(fenToColor.get(s))
      .orElseFail(
        GameError.ParseError(s"Invalid active color '$s' (expected 'w' or 'b')")
      )

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

  def decodeCastling(s: String): IO[GameError, CastlingRights] =
    if s == "-" then ZIO.succeed(CastlingRights(false, false, false, false))
    else if s.distinct.length != s.length then
      ZIO.fail(
        GameError.ParseError(s"Duplicate castling character(s) in '$s'")
      )
    else
      ZIO.succeed(
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

  def decodeEnPassant(s: String): IO[GameError, Option[Position]] =
    if s == "-" then ZIO.succeed(None)
    else
      s match
        case squarePattern(col, row) =>
          ZIO.succeed(Some(Position(col.head, row.toInt)))
        case _ =>
          ZIO.fail(
            GameError.ParseError(s"Invalid en passant target square '$s'")
          )

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

  def decodeBoard(placement: String): IO[GameError, Board] =
    val ranks = placement.split('/')
    if ranks.length != 8 then
      ZIO.fail(
        GameError.ParseError(
          s"Piece placement must have 8 ranks, got ${ranks.length}"
        )
      )
    else
      ZIO.foldLeft((8 to 1 by -1).toList.zip(ranks.toList))(
        Map.empty: Board
      ) { case (board, (row, rank)) =>
        decodeRank(rank, row).map(board ++ _)
      }

  private def decodeRank(
      rank: String,
      row: Int
  ): IO[GameError, Map[Position, Piece]] =
    ZIO
      .foldLeft(rank.toList)((0, Map.empty[Position, Piece])) {
        case ((col, board), ch) if ch.isDigit =>
          ZIO.succeed((col + ch.asDigit, board))
        case ((col, board), ch) =>
          ZIO
            .fromOption(charToPiece(ch))
            .orElseFail(
              GameError.ParseError(
                s"Invalid piece character '$ch' on rank $row"
              )
            )
            .map { piece =>
              (col + 1, board + (Position(('a' + col).toChar, row) -> piece))
            }
      }
      .flatMap { case (col, board) =>
        if col == 8 then ZIO.succeed(board)
        else
          ZIO.fail(
            GameError.ParseError(
              s"Rank $row must describe 8 squares, got $col"
            )
          )
      }

  // ─── Integer fields ────────────────────────────────────────────────────────

  def decodeNonNegativeInt(s: String, field: String): IO[GameError, Int] =
    ZIO
      .fromOption(s.toIntOption)
      .orElseFail(GameError.ParseError(s"Invalid $field '$s'"))

  def decodePositiveInt(s: String, field: String): IO[GameError, Int] =
    ZIO
      .fromOption(s.toIntOption.filter(_ >= 1))
      .orElseFail(GameError.ParseError(s"Invalid $field '$s'"))

package chess.codec

import chess.model.board.{Board, CastlingRights, GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.rules.MoveValidator

/** Converts tokenized FEN fields into a [[GameState]].
  *
  * Each of the three [[FenParser]] implementations tokenizes a FEN string
  * differently (parser-combinator grammar, fastparse grammar, regex), but they
  * all produce the same six raw field strings. The conversion from those raw
  * fields to a validated domain object is shared here so that all parsers
  * behave identically from the caller's perspective.
  *
  * `inCheck` is computed from the board via [[MoveValidator.isInCheck]] so that
  * imported positions display the check highlight correctly. FEN does not
  * encode game status, so `status` is always [[GameStatus.Playing]] — game-end
  * detection happens on the next move attempt.
  */
private[codec] object FenBuilder:

  private val pieceLetters: Map[Char, Piece] = Map(
    'K' -> Piece(Color.White, PieceType.King),
    'Q' -> Piece(Color.White, PieceType.Queen),
    'R' -> Piece(Color.White, PieceType.Rook),
    'B' -> Piece(Color.White, PieceType.Bishop),
    'N' -> Piece(Color.White, PieceType.Knight),
    'P' -> Piece(Color.White, PieceType.Pawn),
    'k' -> Piece(Color.Black, PieceType.King),
    'q' -> Piece(Color.Black, PieceType.Queen),
    'r' -> Piece(Color.Black, PieceType.Rook),
    'b' -> Piece(Color.Black, PieceType.Bishop),
    'n' -> Piece(Color.Black, PieceType.Knight),
    'p' -> Piece(Color.Black, PieceType.Pawn)
  )

  def build(
      placement: String,
      activeColor: String,
      castling: String,
      enPassant: String,
      halfmove: String,
      fullmove: String
  ): Either[String, GameState] =
    for
      board <- parseBoard(placement)
      color <- parseActiveColor(activeColor)
      rights <- parseCastling(castling)
      epTarget <- parseEnPassant(enPassant)
      hm <- parseNonNegativeInt(halfmove, "halfmove clock")
      fm <- parsePositiveInt(fullmove, "fullmove number")
    yield GameState(
      board = board,
      activeColor = color,
      enPassantTarget = epTarget,
      inCheck = MoveValidator.isInCheck(board, color),
      castlingRights = rights,
      halfmoveClock = hm,
      fullmoveNumber = fm
    )

  def parseBoard(placement: String): Either[String, Board] =
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
              rankPart <- parseRank(rank, row)
            yield board ++ rankPart
        }

  private def parseRank(
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
          pieceLetters.get(ch) match
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

  private def parseActiveColor(s: String): Either[String, Color] = s match
    case "w" => Right(Color.White)
    case "b" => Right(Color.Black)
    case _   => Left(s"Invalid active color '$s' (expected 'w' or 'b')")

  private def parseCastling(s: String): Either[String, CastlingRights] =
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

  private val squarePattern = """^([a-h])([1-8])$""".r

  private def parseEnPassant(s: String): Either[String, Option[Position]] =
    if s == "-" then Right(None)
    else
      s match
        case squarePattern(col, row) =>
          Right(Some(Position(col.head, row.toInt)))
        case _ =>
          Left(s"Invalid en passant target square '$s'")

  private def parseNonNegativeInt(
      s: String,
      field: String
  ): Either[String, Int] =
    s.toIntOption.toRight(s"Invalid $field '$s'")

  private def parsePositiveInt(
      s: String,
      field: String
  ): Either[String, Int] =
    s.toIntOption.filter(_ >= 1).toRight(s"Invalid $field '$s'")

package chess.codec

import chess.model.board.{Board, CastlingRights, GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}

/** Serializes a [[GameState]] to a FEN (Forsyth–Edwards Notation) string.
  *
  * This is the counterpart of [[FenParser]] and together they form the
  * round-trip contract:
  * {{{
  *   parser.parse(FenSerializer.serialize(state)) == Right(state)
  * }}}
  *
  * `GameState` does not track the half-move clock or full-move number, so those
  * fields are emitted as `0 1`. When a FEN is parsed and immediately
  * re-serialized those counters are lost, but every other field round-trips
  * exactly.
  */
object FenSerializer:

  def serialize(state: GameState): String =
    val placement = serializeBoard(state.board)
    val active = if state.activeColor == Color.White then "w" else "b"
    val castling = serializeCastling(state.castlingRights)
    val enPassant = state.enPassantTarget.map(_.toString).getOrElse("-")
    s"$placement $active $castling $enPassant 0 1"

  private def serializeBoard(board: Board): String =
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

  private def pieceToChar(piece: Piece): Char =
    val letter = piece.pieceType match
      case PieceType.King   => 'K'
      case PieceType.Queen  => 'Q'
      case PieceType.Rook   => 'R'
      case PieceType.Bishop => 'B'
      case PieceType.Knight => 'N'
      case PieceType.Pawn   => 'P'
    if piece.color == Color.White then letter else letter.toLower

  private def serializeCastling(rights: CastlingRights): String =
    val sb = StringBuilder()
    if rights.whiteKingSide then sb.append('K')
    if rights.whiteQueenSide then sb.append('Q')
    if rights.blackKingSide then sb.append('k')
    if rights.blackQueenSide then sb.append('q')
    if sb.isEmpty then "-" else sb.toString

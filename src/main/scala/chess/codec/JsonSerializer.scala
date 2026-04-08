package chess.codec

import chess.model.board.{Board, CastlingRights, GameState, GameStatus, Position}
import chess.model.piece.{Color, Piece, PieceType}

object JsonSerializer:

  def serialize(state: GameState): String =
    val board = serializeBoard(state.board)
    val active = colorToString(state.activeColor)
    val castling = serializeCastling(state.castlingRights)
    val ep = state.enPassantTarget match
      case Some(pos) => s""""${pos.toString}""""
      case None      => "null"
    val status = state.status match
      case GameStatus.Playing          => """"playing""""
      case GameStatus.Checkmate(color) => s"""{"checkmate": "${colorToString(color)}"}"""
    s"""{
       |  "board": {
       |$board
       |  },
       |  "activeColor": "$active",
       |  "castlingRights": {
       |$castling
       |  },
       |  "enPassantTarget": $ep,
       |  "inCheck": ${state.inCheck},
       |  "status": $status
       |}""".stripMargin

  private def serializeBoard(board: Board): String =
    board.toList
      .sortBy { case (pos, _) => (pos.row, pos.col) }
      .map { case (pos, piece) =>
        s"""    "$pos": "${colorToString(piece.color)} ${pieceTypeName(piece.pieceType)}""""
      }
      .mkString(",\n")

  private def colorToString(c: Color): String = c match
    case Color.White => "white"
    case Color.Black => "black"

  private def pieceTypeName(pt: PieceType): String = pt match
    case PieceType.King   => "king"
    case PieceType.Queen  => "queen"
    case PieceType.Rook   => "rook"
    case PieceType.Bishop => "bishop"
    case PieceType.Knight => "knight"
    case PieceType.Pawn   => "pawn"

  private def serializeCastling(cr: CastlingRights): String =
    List(
      s"    \"whiteKingSide\": ${cr.whiteKingSide}",
      s"    \"whiteQueenSide\": ${cr.whiteQueenSide}",
      s"    \"blackKingSide\": ${cr.blackKingSide}",
      s"    \"blackQueenSide\": ${cr.blackQueenSide}"
    ).mkString(",\n")

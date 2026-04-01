package chess.notation

import chess.model.GameError
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{PieceType, Piece}
import chess.model.rules.MoveValidator
import zio.*

object SanSerializer:

  private val pieceChar: Map[PieceType, Char] = Map(
    PieceType.King -> 'K',
    PieceType.Queen -> 'Q',
    PieceType.Rook -> 'R',
    PieceType.Bishop -> 'B',
    PieceType.Knight -> 'N'
  )

  def toSan(move: Move, state: GameState): IO[GameError, String] =
    val piece = state.board(move.from)
    piece.pieceType match
      case PieceType.Pawn => ZIO.succeed(pawnSan(move, state))
      case pt             => pieceSan(move, state, piece, pt)

  private def pawnSan(move: Move, state: GameState): String =
    val isCapture =
      state.board.contains(move.to) || state.enPassantTarget.contains(move.to)
    val capture = if isCapture then s"${move.from.col}x" else ""
    val dest = squareStr(move.to)
    val promo = move.promotion.map(p => s"=${pieceChar(p)}").getOrElse("")
    s"$capture$dest$promo"

  private def pieceSan(
      move: Move,
      state: GameState,
      piece: Piece,
      pt: PieceType
  ): IO[GameError, String] =
    val letter = pieceChar(pt)
    val capture = if state.board.contains(move.to) then "x" else ""
    val dest = squareStr(move.to)
    disambiguation(move, state, piece).map { disambig =>
      s"$letter$disambig$capture$dest"
    }

  private def disambiguation(
      move: Move,
      state: GameState,
      piece: Piece
  ): IO[GameError, String] =
    val candidatePositions = state.board.toList.collect {
      case (pos, p) if p == piece && pos != move.from => pos
    }
    ZIO
      .filter(candidatePositions)(pos =>
        MoveValidator
          .validate(state, Move(pos, move.to))
          .as(true)
          .catchAll(_ => ZIO.succeed(false))
      )
      .map { others =>
        if others.isEmpty then ""
        else if others.forall(_.col != move.from.col) then
          move.from.col.toString
        else if others.forall(_.row != move.from.row) then
          move.from.row.toString
        else s"${move.from.col}${move.from.row}"
      }

  private def squareStr(pos: Position): String = s"${pos.col}${pos.row}"

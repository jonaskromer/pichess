package chess.controller

import chess.model.GameError
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.PieceType
import chess.model.rules.MoveValidator

object SanResolver:
  def resolve(parsed: ParsedMove, state: GameState): Either[GameError, Move] =
    parsed match
      case ParsedMove.Coordinate(from, to, promotion) =>
        Right(Move(from, to, promotion))
      case ParsedMove.Castling(kingside) =>
        val side = if kingside then "Kingside" else "Queenside"
        Left(GameError.InvalidMove(s"$side castling is not yet implemented"))
      case ParsedMove.San(piece, dest, disambigFile, disambigRank, promotion) =>
        resolveSan(piece, dest, disambigFile, disambigRank, state, promotion)

  private def resolveSan(
      piece: PieceType,
      dest: Position,
      disambigFile: Option[Char],
      disambigRank: Option[Int],
      state: GameState,
      promotion: Option[PieceType] = None
  ): Either[GameError, Move] =
    val candidates = state.board.toList
      .collect {
        case (from, p)
            if p.color == state.activeColor && p.pieceType == piece =>
          from
      }
      .filter { from =>
        disambigFile.forall(_ == from.col) &&
        disambigRank.forall(_ == from.row) &&
        MoveValidator.validate(state, Move(from, dest)).isRight
      }
    candidates match
      case List(from) => Right(Move(from, dest, promotion))
      case Nil =>
        Left(GameError.InvalidMove(s"No ${piece} can move to $dest"))
      case _ =>
        Left(
          GameError.InvalidMove(
            s"Ambiguous: multiple ${piece}s can reach $dest — add a disambiguation character"
          )
        )

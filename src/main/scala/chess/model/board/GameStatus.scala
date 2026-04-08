package chess.model.board

import chess.model.piece.Color

enum DrawReason:
  case Stalemate
  case FiftyMoveRule
  case InsufficientMaterial

enum GameStatus:
  case Playing
  case Checkmate(winner: Color)
  case Draw(reason: DrawReason)

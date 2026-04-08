package chess.model.board

import chess.model.piece.Color

enum GameStatus:
  case Playing
  case Checkmate(winner: Color)
  case Draw(reason: String)

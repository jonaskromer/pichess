package chess.model.board

import chess.model.piece.Color

enum DrawReason:
  case Stalemate
  case FiftyMoveRule
  case InsufficientMaterial
  case ThreefoldRepetition
  case FivefoldRepetition

/** The current phase of a chess game.
  *
  * Transitions always go `Playing → Terminal` — once a game is over
  * (`Checkmate`, `Draw`, or `Resignation`) it stays over. Future terminal
  * cases (e.g. `Timeout`) should extend this enum and be treated as terminal
  * by [[isOver]].
  */
enum GameStatus:
  case Playing
  case Checkmate(winner: Color)
  case Draw(reason: DrawReason)
  case Resignation(winner: Color)

  def isPlaying: Boolean = this == Playing
  def isOver: Boolean = !isPlaying

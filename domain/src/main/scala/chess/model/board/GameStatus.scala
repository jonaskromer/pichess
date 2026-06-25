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
  * (`Checkmate`, `Draw`, `Resignation`, or `Timeout`) it stays over. `Timeout`
  * (a side ran out of clock) is a service-imposed terminal like `Resignation`,
  * not a rules outcome — `chess.model.rules.Game` never produces it.
  */
enum GameStatus:
  case Playing
  case Checkmate(winner: Color)
  case Draw(reason: DrawReason)
  case Resignation(winner: Color)
  case Timeout(winner: Color)

  def isPlaying: Boolean = this == Playing
  def isOver: Boolean = !isPlaying

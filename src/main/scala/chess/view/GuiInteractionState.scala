package chess.view

import chess.model.board.Position

enum GuiInteractionState:
  case Idle
  case Selected(from: Position)

object GuiInteractionState:
  /** Processes a click on the board, returning the next state and an optional
    * move string (e.g., "e2e4") if a full move sequence was completed.
    */
  def handleClick(
      state: GuiInteractionState,
      pos: Position
  ): (GuiInteractionState, Option[String]) =
    state match
      case Idle =>
        (Selected(pos), None)
      case Selected(from) =>
        if from == pos then (Idle, None) // Deselect
        else (Idle, Some(s"${from.col}${from.row}${pos.col}${pos.row}"))

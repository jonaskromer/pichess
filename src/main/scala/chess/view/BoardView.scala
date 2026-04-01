package chess.view

import chess.model.piece.{Color, Piece}
import chess.model.board.{GameState, Position}

object BoardView:
  // Pastel cream for light squares, pastel sage for dark squares
  private val lightBg = "\u001b[48;2;235;225;200m"
  private val darkBg = "\u001b[48;2;150;185;155m"
  // Bold bright white for White pieces, bold near-black for Black pieces
  private val whiteFg = "\u001b[1;97m"
  private val blackFg = "\u001b[1;30m"
  private val reset = "\u001b[0m"

  def render(state: GameState, flipped: Boolean = false): String =
    val cols: Seq[Char] =
      if flipped then ('a' to 'h').toList.reverse else ('a' to 'h').toList
    val rows = if flipped then (1 to 8) else (8 to 1 by -1)
    val colLabels = " " + cols.map(c => s" $c ").mkString + "\n"
    val ranks = rows.map: row =>
      val squares = cols.map: col =>
        val pos = Position(col, row)
        val isDark = (col - 'a' + row) % 2 == 1
        val bg = if isDark then darkBg else lightBg
        state.board.get(pos) match
          case None => s"$bg   $reset"
          case Some(piece) =>
            val fg = if piece.color == Color.White then whiteFg else blackFg
            s"$bg$fg ${PieceUnicode(piece)} $reset"
      s"$row${squares.mkString} $row"
    colLabels + ranks.mkString("\n") + "\n" + colLabels

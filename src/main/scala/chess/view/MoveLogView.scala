package chess.view

import chess.model.piece.Color

object MoveLogView:
  // Black text on white background
  private val whiteStyle = "\u001b[30;47m"
  // White text on black background
  private val blackStyle = "\u001b[97;40m"
  private val reset = "\u001b[0m"

  def render(log: List[(Color, String)]): String =
    if log.isEmpty then ""
    else
      val recent = log.takeRight(2)
      val entries = recent.map: (color, san) =>
        val label = styledLabel(color)
        s"$label: $san"
      entries.mkString(" -> ")

  private def styledLabel(color: Color): String =
    color match
      case Color.White => s"${whiteStyle}White${reset}"
      case Color.Black => s"${blackStyle}Black${reset}"

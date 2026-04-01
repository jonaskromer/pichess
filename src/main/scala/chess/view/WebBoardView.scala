package chess.view

import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece}

object WebBoardView:

  def toJson(
      state: GameState,
      moveLog: List[(Color, String)],
      error: Option[String]
  ): String =
    val squaresJson = buildSquaresJson(state)
    val activeColor = colorStr(state.activeColor)
    val moveLogJson = buildMoveLogJson(moveLog)
    val errorJson = error.map(e => s""""${escapeJson(e)}"""").getOrElse("null")
    s"""{"squares":[$squaresJson],"activeColor":"$activeColor","moveLog":[$moveLogJson],"error":$errorJson}"""

  private def buildSquaresJson(state: GameState): String =
    val entries = for
      row <- (8 to 1 by -1)
      col <- 'a' to 'h'
    yield
      val pos = Position(col, row)
      val squareColor = if (col - 'a' + row) % 2 == 1 then "dark" else "light"
      state.board.get(pos) match
        case Some(piece) =>
          val pieceChar = PieceUnicode(piece).toString
          val pieceColor = colorStr(piece.color)
          s"""{"pos":"$pos","squareColor":"$squareColor","piece":"$pieceChar","pieceColor":"$pieceColor"}"""
        case None =>
          s"""{"pos":"$pos","squareColor":"$squareColor","piece":null,"pieceColor":null}"""
    entries.mkString(",")

  private def buildMoveLogJson(moveLog: List[(Color, String)]): String =
    moveLog
      .map((color, san) =>
        s"""{"color":"${colorStr(color)}","san":"${escapeJson(san)}"}"""
      )
      .mkString(",")

  private def colorStr(color: Color): String = color match
    case Color.White => "white"
    case Color.Black => "black"

  def escapeJson(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
    }

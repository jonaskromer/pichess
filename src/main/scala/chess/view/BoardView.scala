package chess.view

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{GameState, Position}

object BoardView:
  // Pastel cream for light squares, pastel sage for dark squares
  private val lightBg = "\u001b[48;2;235;225;200m"
  private val darkBg = "\u001b[48;2;150;185;155m"
  // Bold bright white for White pieces, bold near-black for Black pieces
  private val whiteFg = "\u001b[1;97m"
  private val blackFg = "\u001b[1;30m"
  private val reset = "\u001b[0m"

  def render(state: GameState): String =
    val colLabels = " " + ('a' to 'h').map(c => s" $c ").mkString + "\n"
    val ranks = (8 to 1 by -1).map: row =>
      val squares = ('a' to 'h').map: col =>
        val pos = Position(col, row)
        val isDark = (col - 'a' + row) % 2 == 1
        val bg = if isDark then darkBg else lightBg
        state.board.get(pos) match
          case None => s"$bg   $reset"
          case Some(piece) =>
            val fg = if piece.color == Color.White then whiteFg else blackFg
            s"$bg$fg ${unicodeFor(piece)} $reset"
      s"$row${squares.mkString} $row"
    colLabels + ranks.mkString("\n") + "\n" + colLabels

  private def unicodeFor(piece: Piece): Char =
    import Color.*
    import PieceType.*
    (piece.color, piece.pieceType) match
      case (White, King)   => '♔'
      case (White, Queen)  => '♕'
      case (White, Rook)   => '♖'
      case (White, Bishop) => '♗'
      case (White, Knight) => '♘'
      case (White, Pawn)   => '♙'
      case (Black, King)   => '♚'
      case (Black, Queen)  => '♛'
      case (Black, Rook)   => '♜'
      case (Black, Bishop) => '♝'
      case (Black, Knight) => '♞'
      case (Black, Pawn)   => '♟'

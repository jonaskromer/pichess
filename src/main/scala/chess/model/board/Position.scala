package chess.model.board

case class Position(col: Char, row: Int):
  override def toString: String = s"$col$row"

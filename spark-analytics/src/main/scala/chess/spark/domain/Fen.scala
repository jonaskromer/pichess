package chess.spark.domain

import scala.collection.mutable.ListBuffer

/** Tiny FEN board reader — just enough to drive square-level analytics. The
  * `codec` module's full parser lives in the Scala-3.8.2 world this module
  * can't depend on, so we re-implement the one field we need (piece placement).
  *
  * FEN's first field is 8 ranks (8 → 1) separated by '/', each rank a left-to-
  * right (a → h) run of piece letters and digit gaps.
  */
object Fen:

  /** Algebraic names (e.g. "e4") of every occupied square in the position. */
  def occupiedSquares(fen: String): List[String] =
    pieces(fen).map(_._1)

  /** (square, pieceChar) for every occupied square; piece case = colour. */
  def pieces(fen: String): List[(String, Char)] =
    val board = fen.takeWhile(_ != ' ')
    val ranks = board.split('/')
    if ranks.length != 8 then Nil
    else
      val out = ListBuffer.empty[(String, Char)]
      ranks.iterator.zipWithIndex.foreach { case (rankStr, ri) =>
        val rank = 8 - ri
        var file = 0
        rankStr.foreach { c =>
          if c.isDigit then file += (c - '0')
          else
            if file < 8 then out += ((s"${('a' + file).toChar}$rank", c))
            file += 1
        }
      }
      out.toList

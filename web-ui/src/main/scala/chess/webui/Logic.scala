package chess.webui

import chess.api.{BoardStateDto, MoveEntryDto}

/** Pure helpers used by the Laminar `Main` component.
  *
  * These have no DOM or Laminar dependency so they can be unit-tested in
  * plain zio-test on Scala.js — the `Main.scala` file itself is harder to
  * exercise because it wires together Laminar signals and DOM events.
  */
object Logic:

  /** `true` when moving the piece at `from` to `to` constitutes a pawn
    * promotion — i.e. the piece is a pawn and the destination rank is the
    * opponent's back rank.
    */
  def isPawnPromotion(
      from: String,
      to: String,
      state: BoardStateDto,
  ): Boolean =
    state.squares.find(_.pos == from).flatMap(_.piece) match
      case Some(p) if p == "♙" || p == "♟" =>
        val row = to.charAt(1)
        row == '8' || row == '1'
      case _ => false

  /** Group a chronological move log into `(moveNumber, white, blackOpt)`
    * triples — one row per White-Black pair; a dangling white half-move
    * appears alone at the end with `None` in the black slot.
    */
  def groupMovesByTwo(
      moves: List[MoveEntryDto],
  ): List[(Int, MoveEntryDto, Option[MoveEntryDto])] =
    moves
      .grouped(2)
      .zipWithIndex
      .map { case (pair, idx) => (idx + 1, pair.head, pair.lift(1)) }
      .toList

  private val whitePromotions = List(
    "Q" -> "♕",
    "R" -> "♖",
    "B" -> "♗",
    "N" -> "♘",
  )
  private val blackPromotions = List(
    "Q" -> "♛",
    "R" -> "♜",
    "B" -> "♝",
    "N" -> "♞",
  )

  /** (SAN-key, Unicode glyph) pairs to offer in the promotion dialog — four
    * entries, filtered to the moving pawn's color.
    */
  def selectPromotionPieces(isWhite: Boolean): List[(String, String)] =
    if isWhite then whitePromotions else blackPromotions

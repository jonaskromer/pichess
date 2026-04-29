package chess.webui

import chess.api.{BoardStateDto, MoveEntryDto, SquareDto}

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

  /** Map a wire-format draw reason to a user-readable phrase. The kebab-case
    * tokens come from `DrawReason.name` on the JVM side. Unknown tokens fall
    * through unchanged so a future reason still renders something sensible.
    */
  def humanizeDrawReason(reason: String): String =
    reason match
      case "fiftyMoveRule"        => "50-move rule"
      case "threefoldRepetition"  => "threefold repetition"
      case "fivefoldRepetition"   => "fivefold repetition"
      case "stalemate"            => "stalemate"
      case "insufficientMaterial" => "insufficient material"
      case other                  => other

  // The starting position has these piece counts per color. We diff the
  // current squares against this multiset to derive captured material — no
  // server-side support needed.
  private val startingWhiteGlyphs: List[String] =
    List.fill(8)("♙") ++ List.fill(2)("♖") ++ List.fill(2)("♘") ++
      List.fill(2)("♗") ++ List("♕", "♔")
  private val startingBlackGlyphs: List[String] =
    List.fill(8)("♟") ++ List.fill(2)("♜") ++ List.fill(2)("♞") ++
      List.fill(2)("♝") ++ List("♛", "♚")

  // Multiset diff: the elements of `expected` that aren't matched by an
  // element in `actual`, counting duplicates correctly.
  private def multisetDiff[A](expected: List[A], actual: List[A]): List[A] =
    actual
      .foldLeft(expected) { (remaining, glyph) =>
        val idx = remaining.indexOf(glyph)
        if idx < 0 then remaining else remaining.patch(idx, Nil, 1)
      }

  /** True for the six white glyphs from the starting set. Lets the captured
    * tray pick the right text-shadow class without round-tripping a `pieceColor`.
    */
  def isWhiteGlyph(glyph: String): Boolean =
    startingWhiteGlyphs.contains(glyph)

  /** Captured pieces, per color, derived from the current board.
    *
    * Returns `(whiteCaptured, blackCaptured)` where `whiteCaptured` is the
    * white pieces that have been *taken* (i.e. captured by black), in
    * descending order of value so the tray reads naturally.
    */
  def capturedFromSquares(squares: List[SquareDto]): (List[String], List[String]) =
    val present     = squares.flatMap(_.piece)
    val whiteAlive  = present.filter(startingWhiteGlyphs.contains)
    val blackAlive  = present.filter(startingBlackGlyphs.contains)
    val whiteLost   = sortByValue(multisetDiff(startingWhiteGlyphs, whiteAlive))
    val blackLost   = sortByValue(multisetDiff(startingBlackGlyphs, blackAlive))
    (whiteLost, blackLost)

  // Highest-value piece first so multiple captures cluster as K-Q-R-B-N-P.
  // Kings get a sentinel value so the (illegal but possible) edge case of
  // a missing king sorts to the front rather than crashing on lookup.
  private val pieceValues: Map[String, Int] = Map(
    "♔" -> 100, "♚" -> 100,
    "♕" -> 9,   "♛" -> 9,
    "♖" -> 5,   "♜" -> 5,
    "♗" -> 3,   "♝" -> 3,
    "♘" -> 3,   "♞" -> 3,
    "♙" -> 1,   "♟" -> 1,
  )

  private def sortByValue(glyphs: List[String]): List[String] =
    glyphs.sortBy(g => -pieceValues(g))

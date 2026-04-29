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
      case Some("pawn") =>
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

  /** (SAN-key, piece-type-name) pairs offered in the promotion dialog — four
    * entries, the same four for both colors. The piece-type name is used as
    * the symbol id when rendering `<use href="/web/pieces/<name>.svg#<name>"/>`.
    */
  val promotionChoices: List[(String, String)] = List(
    "Q" -> "queen",
    "R" -> "rook",
    "B" -> "bishop",
    "N" -> "knight",
  )

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

  // The starting position has these piece-type counts per side. We diff the
  // current squares against this multiset to derive captured material — no
  // server-side support needed.
  private val startingPieceTypes: List[String] =
    List.fill(8)("pawn") ++ List.fill(2)("rook") ++ List.fill(2)("knight") ++
      List.fill(2)("bishop") ++ List("queen", "king")

  // Multiset diff: the elements of `expected` that aren't matched by an
  // element in `actual`, counting duplicates correctly.
  private def multisetDiff[A](expected: List[A], actual: List[A]): List[A] =
    actual
      .foldLeft(expected) { (remaining, item) =>
        val idx = remaining.indexOf(item)
        if idx < 0 then remaining else remaining.patch(idx, Nil, 1)
      }

  /** Captured pieces, per color, derived from the current board.
    *
    * Returns `(whiteCaptured, blackCaptured)` where `whiteCaptured` is the
    * white pieces that have been *taken* (i.e. captured by black), in
    * descending order of value so the tray reads naturally.
    */
  def capturedFromSquares(
      squares: List[SquareDto]
  ): (List[String], List[String]) =
    val whiteAlive = squares.collect {
      case s if s.pieceColor.contains("white") && s.piece.nonEmpty => s.piece.get
    }
    val blackAlive = squares.collect {
      case s if s.pieceColor.contains("black") && s.piece.nonEmpty => s.piece.get
    }
    val whiteLost = sortByValue(multisetDiff(startingPieceTypes, whiteAlive))
    val blackLost = sortByValue(multisetDiff(startingPieceTypes, blackAlive))
    (whiteLost, blackLost)

  // Highest-value piece first so multiple captures cluster as K-Q-R-B-N-P.
  // The king gets a sentinel value so the (illegal but possible) edge case
  // of a missing king sorts to the front rather than crashing on lookup.
  private val pieceValues: Map[String, Int] = Map(
    "king"   -> 100,
    "queen"  -> 9,
    "rook"   -> 5,
    "bishop" -> 3,
    "knight" -> 3,
    "pawn"   -> 1,
  )

  private def sortByValue(types: List[String]): List[String] =
    types.sortBy(t => -pieceValues(t))

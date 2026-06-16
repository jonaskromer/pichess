package chess.bot.engine

import chess.model.board.{BoardLike, PositionView}
import chess.model.piece.{Color, PieceType}

/** Decorator that adds endgame-specific patches on top of the
  * underlying evaluator. The tuner-driven [[ArrayTaperedEvaluator]]
  * captures middlegame and most-endgame positions well but loses
  * resolution in low-piece-count terminal phases where the
  * "drive losing king to the edge / corner" or "pawn can promote
  * before the lone king catches it" patterns dominate the result.
  *
  * Four patterns recognised, additive bonuses to the base eval:
  *   1. KQK / KRK — drive the lone king toward edge / corner.
  *   2. KPK rule-of-square — pawn promotes before lone king can
  *      catch it → huge bonus (close to queen value).
  *   3. Opposite-color bishops with no rooks / queens — pull a
  *      lopsided material eval back toward draw.
  *
  * All bonuses are white-POV centipawns — same convention as the
  * underlying `Evaluator.evaluate`. The caller (search) flips on
  * black to get side-to-move POV. */
final class EndgameAwareEvaluator(inner: Evaluator) extends Evaluator:

  override def evaluate(state: PositionView): Int =
    val base = inner.evaluate(state)
    base + endgamePatch(state, base)

  private def endgamePatch(state: PositionView, base: Int): Int =
    val b = state.board
    val pieces = b.occupancy.popCount
    if pieces > 8 then 0
    else
      val kxk = kingAndPawnsOnly(b)
      val kqk = kingPlusQueenVsKing(b)
      val krk = kingPlusRookVsKing(b)
      val kpk = kingPlusPawnVsKing(b)
      val ocb = oppositeColorBishopsBalanced(b)
      kqk + krk + kpk +
        // OCB: scale the existing eval toward draw. Implemented
        // additively as `-base/2` so it composes with the linear
        // patch sum.
        (if ocb then -base / 2 else 0)

  /** White - black corner-drive bonus when one side has K+Q vs K.
    * Encourages corralling the losing king to a corner where mate
    * is forced in ≤ 10 plies. */
  private def kingPlusQueenVsKing(b: BoardLike): Int =
    val wHas = b.queensW.nonEmpty && noOtherPieces(b, white = true, exceptQueen = true)
    val bBare = bareKing(b, white = false)
    val bHas = b.queensB.nonEmpty && noOtherPieces(b, white = false, exceptQueen = true)
    val wBare = bareKing(b, white = true)
    if wHas && bBare then cornerDriveBonus(b.kingB.lowestBitIdx, white = true)
    else if bHas && wBare then -cornerDriveBonus(b.kingW.lowestBitIdx, white = false)
    else 0

  /** Same shape for K+R vs K — drive to the edge (rooks mate via
    * confining to one rank/file). */
  private def kingPlusRookVsKing(b: BoardLike): Int =
    val wHas = b.rooksW.nonEmpty && noOtherPieces(b, white = true, exceptRook = true)
    val bBare = bareKing(b, white = false)
    val bHas = b.rooksB.nonEmpty && noOtherPieces(b, white = false, exceptRook = true)
    val wBare = bareKing(b, white = true)
    if wHas && bBare then edgeDriveBonus(b.kingB.lowestBitIdx, white = true)
    else if bHas && wBare then -edgeDriveBonus(b.kingW.lowestBitIdx, white = false)
    else 0

  /** Rule-of-the-square: if the lone king can't catch a passed pawn
    * before it promotes, return a large bonus for the pawn's owner.
    * `dist(king, promoSquare) > dist(pawn, promoSquare)` means the
    * king arrives a tempo too late. */
  private def kingPlusPawnVsKing(b: BoardLike): Int =
    val whiteWinning =
      b.pawnsW.popCount >= 1 && noOtherPieces(b, white = true, exceptPawn = true) &&
        bareKing(b, white = false)
    val blackWinning =
      b.pawnsB.popCount >= 1 && noOtherPieces(b, white = false, exceptPawn = true) &&
        bareKing(b, white = true)
    if whiteWinning then ruleOfSquareBonus(b, white = true)
    else if blackWinning then -ruleOfSquareBonus(b, white = false)
    else 0

  private def ruleOfSquareBonus(b: BoardLike, white: Boolean): Int =
    val pawns = if white then b.pawnsW.raw else b.pawnsB.raw
    val loneKingSq = if white then b.kingB.lowestBitIdx else b.kingW.lowestBitIdx
    var rem = pawns
    var bestBonus = 0
    while rem != 0L do
      val sq = java.lang.Long.numberOfTrailingZeros(rem)
      rem &= rem - 1L
      val promoSq = if white then sq % 8 + 56 else sq % 8
      val pawnDist =
        if white then 7 - (sq / 8)
        else (sq / 8)
      val kingDist = chebyshev(loneKingSq, promoSq)
      if kingDist > pawnDist then
        // Pawn promotes uncaught — close to a queen up.
        bestBonus = math.max(bestBonus, 700)
    bestBonus

  /** OCB detected when both sides have ≥1 bishop, opposite colours,
    * and the rest of material is mostly minor pieces (no rooks /
    * queens, since those break OCB drawishness). */
  private def oppositeColorBishopsBalanced(b: BoardLike): Boolean =
    val wB = b.bishopsW.raw
    val bB = b.bishopsB.raw
    if java.lang.Long.bitCount(wB) != 1 || java.lang.Long.bitCount(bB) != 1 then false
    else if b.rooksW.nonEmpty || b.rooksB.nonEmpty then false
    else if b.queensW.nonEmpty || b.queensB.nonEmpty then false
    else
      // Compare square colors of the two bishops. Light = (col+row) even.
      val wSq = java.lang.Long.numberOfTrailingZeros(wB)
      val bSq = java.lang.Long.numberOfTrailingZeros(bB)
      ((wSq / 8 + wSq % 8) & 1) != ((bSq / 8 + bSq % 8) & 1)

  // ── Geometric helpers ────────────────────────────────────────────

  /** Bonus that grows as the losing king approaches a corner. */
  private inline def cornerDriveBonus(losingKingSq: Int, white: Boolean): Int =
    val row = losingKingSq / 8
    val col = losingKingSq % 8
    val edgeR = math.min(row, 7 - row)
    val edgeC = math.min(col, 7 - col)
    val cornerDist = edgeR + edgeC
    (8 - cornerDist) * 20

  /** Bonus that grows as the losing king approaches an edge. */
  private inline def edgeDriveBonus(losingKingSq: Int, white: Boolean): Int =
    val row = losingKingSq / 8
    val col = losingKingSq % 8
    val edge = math.min(math.min(row, 7 - row), math.min(col, 7 - col))
    (4 - edge) * 15

  /** Chebyshev distance — max of row/col diff. Matches king-move
    * geometry. */
  private inline def chebyshev(a: Int, b: Int): Int =
    val dr = math.abs(a / 8 - b / 8)
    val dc = math.abs(a % 8 - b % 8)
    math.max(dr, dc)

  /** True when the side has only a king and nothing else. */
  private def bareKing(b: BoardLike, white: Boolean): Boolean =
    val rest =
      if white then b.pawnsW.raw | b.knightsW.raw | b.bishopsW.raw | b.rooksW.raw | b.queensW.raw
      else      b.pawnsB.raw | b.knightsB.raw | b.bishopsB.raw | b.rooksB.raw | b.queensB.raw
    rest == 0L

  /** True when the side has no pieces other than king + the
    * specified single piece type. */
  private def noOtherPieces(
      b: BoardLike,
      white: Boolean,
      exceptPawn: Boolean = false,
      exceptKnight: Boolean = false,
      exceptBishop: Boolean = false,
      exceptRook: Boolean = false,
      exceptQueen: Boolean = false,
  ): Boolean =
    val pawn   = if white then b.pawnsW.raw   else b.pawnsB.raw
    val knight = if white then b.knightsW.raw else b.knightsB.raw
    val bishop = if white then b.bishopsW.raw else b.bishopsB.raw
    val rook   = if white then b.rooksW.raw   else b.rooksB.raw
    val queen  = if white then b.queensW.raw  else b.queensB.raw
    (exceptPawn   || pawn   == 0L) &&
    (exceptKnight || knight == 0L) &&
    (exceptBishop || bishop == 0L) &&
    (exceptRook   || rook   == 0L) &&
    (exceptQueen  || queen  == 0L)

  /** Helper for KPK detection: side has only kings and pawns. */
  private def kingAndPawnsOnly(b: BoardLike): Boolean =
    (b.knightsW.isEmpty && b.bishopsW.isEmpty && b.rooksW.isEmpty && b.queensW.isEmpty) &&
    (b.knightsB.isEmpty && b.bishopsB.isEmpty && b.rooksB.isEmpty && b.queensB.isEmpty)

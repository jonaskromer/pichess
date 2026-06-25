package chess.analysis

import chess.codec.Nag

/** Quality class of a played move, mapped to its PGN NAG glyph (Phase 1
  * [[Nag]]). The full vocabulary is represented for PGN/GUI; `Brilliant` (!!)
  * and `Interesting` (!?) require a sacrifice/sharpness heuristic and are not
  * emitted by [[MoveQuality.classify]] yet — the robust win-%-drop core is.
  */
enum MoveClass:
  case Book, Best, Good, Brilliant, Interesting, Inaccuracy, Mistake, Blunder

  /** PGN NAG code, or None for `Book`/`Best` (no glyph). */
  def nag: Option[Int] = this match
    case Book        => None
    case Best        => None
    case Good        => Some(Nag.Good)        // !
    case Brilliant   => Some(Nag.Brilliant)   // !!
    case Interesting => Some(Nag.Interesting) // !?
    case Inaccuracy  => Some(Nag.Dubious)     // ?!
    case Mistake     => Some(Nag.Mistake)     // ?
    case Blunder     => Some(Nag.Blunder)     // ??

object MoveQuality:

  private val BlunderDrop    = 20.0 // win-% points lost
  private val MistakeDrop    = 10.0
  private val InaccuracyDrop = 5.0
  private val OnlyMoveGap    = 150 // cp gap to the 2nd-best move → "!" (only move)

  /** Classify a move from its win-% drop, whether it was the engine's top move,
    * the centipawn gap to the second-best move, and whether it's still in book.
    */
  def classify(
      winPctDrop: Double,
      isBest: Boolean,
      gapToSecondCp: Int,
      isBook: Boolean
  ): MoveClass =
    if isBook then MoveClass.Book
    else if winPctDrop >= BlunderDrop then MoveClass.Blunder
    else if winPctDrop >= MistakeDrop then MoveClass.Mistake
    else if winPctDrop >= InaccuracyDrop then MoveClass.Inaccuracy
    else if isBest && gapToSecondCp >= OnlyMoveGap then MoveClass.Good
    else MoveClass.Best

  /** Mean accuracy of a side's moves (100 if it made none). */
  def averageAccuracy(accuracies: List[Double]): Double =
    if accuracies.isEmpty then 100.0 else accuracies.sum / accuracies.size

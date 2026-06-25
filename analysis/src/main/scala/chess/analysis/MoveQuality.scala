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

  // Volatility window + floor for the weighted game-accuracy (below).
  private val WeightRadius = 2    // plies either side of a move
  private val MinWeight    = 0.5  // so a quiet move still counts a little

  /** Per-move importance weights from a white-relative win-% timeline (`50.0 ::
    * each move's resulting win%`, so length = moves + 1). The weight of move `i`
    * is the local **volatility** — the std-dev of win% in a window straddling
    * the move — floored at [[MinWeight]]. Sharp, decisive moments weigh more
    * than a long quiet shuffle, mirroring how a human would judge the game. */
  def volatilityWeights(winSeq: List[Double]): List[Double] =
    val arr   = winSeq.toVector
    val moves = math.max(0, arr.length - 1)
    (0 until moves).toList.map { i =>
      val lo = math.max(0, i - WeightRadius)
      val hi = math.min(arr.length, i + WeightRadius + 2)
      math.max(MinWeight, stdDev(arr.slice(lo, hi)))
    }

  /** Game accuracy for one side from its `(accuracy, weight)` pairs — the mean
    * of a **volatility-weighted mean** and a **harmonic mean** (the Lichess
    * recipe). The weighted mean focuses the score on the decisive moments; the
    * harmonic mean keeps a single blunder from being averaged away. 100 when the
    * side made no moves. */
  def weightedAccuracy(accuracyWeighted: List[(Double, Double)]): Double =
    if accuracyWeighted.isEmpty then 100.0
    else
      val accs   = accuracyWeighted.map(_._1)
      val totalW = accuracyWeighted.map(_._2).sum
      val wMean =
        if totalW <= 0.0 then accs.sum / accs.size
        else accuracyWeighted.map((a, w) => a * w).sum / totalW
      val hMean = accs.size / accs.map(a => 1.0 / math.max(a, 1.0)).sum
      (wMean + hMean) / 2.0

  // Always called on a non-empty window (volatilityWeights' slices span ≥ 2
  // entries), so no empty-guard is needed.
  private def stdDev(xs: Vector[Double]): Double =
    val mean = xs.sum / xs.length
    math.sqrt(xs.map(x => (x - mean) * (x - mean)).sum / xs.length)

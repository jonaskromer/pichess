package chess.bot.train

/** Texel-style coordinate-descent weight tuner.
  *
  * Texel tuning fits a linear evaluator's weights to a labelled
  * corpus of (position, eventual-outcome) pairs. The eval score in
  * centipawns is passed through a logistic sigmoid to land in [0, 1];
  * the loss is the mean squared error of that mapped score against
  * the actual outcome. Each iteration picks one weight, tries it ±
  * step, keeps whichever step (if any) reduces the loss. When no
  * weight improves at the current step, halve the step and try
  * again; when the step drops below 1, we're done.
  *
  * Why coordinate descent? It's the original Texel approach — much
  * simpler to implement than full SGD (no autodiff, no learning-rate
  * schedule), and the eval function is shallow enough that gradient
  * methods don't materially outperform. For our use it converges in
  * ~10–20 outer iterations on a few million samples in minutes.
  *
  * Pure: the tuner doesn't read the DB, doesn't sample positions, and
  * doesn't extract features. The caller assembles the [[Sample]] list
  * (e.g. by streaming the training_positions table + computing feature
  * counts from a FEN lookup, or directly from a PGN replay) and the
  * tuner returns optimised weights. That keeps the math testable in
  * isolation from PGN parsing and DB I/O.
  */
object TexelTuner:

  /** One labelled training position.
    *
    * @param features map of feature name → count for this position.
    *                 The evaluator computes `Σ_f (count_f * weight_f)`.
    * @param outcome the eventual game outcome from the side-to-move
    *                perspective: 1.0 = won, 0.5 = drew, 0.0 = lost.
    */
  final case class Sample(features: Map[String, Int], outcome: Double)

  /** Tuner output. `iterations` is the number of full passes
    * completed; `finalLoss` is the mean squared sigmoid-mapped
    * error on the training data with the returned weights. */
  final case class TuningResult(
      weights: Map[String, Int],
      finalLoss: Double,
      iterations: Int,
  )

  /** Run coordinate descent.
    *
    * @param samples       training data
    * @param initial       starting weight vector. Must cover every
    *                      feature key any sample carries — keys not
    *                      in `initial` are silently treated as weight
    *                      0 during eval, but they're not adjustable.
    * @param K             sigmoid steepness. 0.4 is Texel's standard
    *                      value for centipawn-scale evals.
    * @param maxIterations safety cap. With a sensible initial vector
    *                      and a step schedule, convergence happens in
    *                      well under 20 passes; 100 is paranoia.
    * @param initialStep   centipawn step size for the first round of
    *                      adjustments. Halves on no-progress until it
    *                      falls below 1 (then the search terminates).
    */
  def tune(
      samples: Seq[Sample],
      initial: Map[String, Int],
      K: Double = 0.4,
      maxIterations: Int = 100,
      initialStep: Int = 16,
  ): TuningResult =
    var weights = initial
    var step    = initialStep
    var iters   = 0
    var loss    = totalLoss(samples, weights, K)
    while step >= 1 && iters < maxIterations do
      iters += 1
      val (next, nextLoss) = oneSweep(samples, weights, K, step, loss)
      if nextLoss < loss then
        weights = next
        loss = nextLoss
      else
        // Couldn't improve at this step size — narrow the search.
        step = step / 2
    TuningResult(weights, loss, iters)

  /** Single coordinate-descent sweep: try each weight ± step. */
  private[train] def oneSweep(
      samples: Seq[Sample],
      weights: Map[String, Int],
      K: Double,
      step: Int,
      currentLoss: Double,
  ): (Map[String, Int], Double) =
    var working = weights
    var loss    = currentLoss
    weights.keys.foreach { feature =>
      val plus  = working.updated(feature, working(feature) + step)
      val minus = working.updated(feature, working(feature) - step)
      val plusLoss  = totalLoss(samples, plus, K)
      val minusLoss = totalLoss(samples, minus, K)
      if plusLoss < loss && plusLoss <= minusLoss then
        working = plus
        loss = plusLoss
      else if minusLoss < loss then
        working = minus
        loss = minusLoss
    }
    (working, loss)

  /** Mean squared sigmoid-mapped error over the sample set.
    * Public-ish so the test suite can compare loss before/after a
    * tuning run without re-implementing it. */
  private[train] def totalLoss(
      samples: Seq[Sample],
      weights: Map[String, Int],
      K: Double,
  ): Double =
    if samples.isEmpty then 0.0
    else
      var sumSq = 0.0
      val it = samples.iterator
      while it.hasNext do
        val s = it.next()
        val predicted = sigmoid(K * evaluate(s.features, weights))
        val diff      = predicted - s.outcome
        sumSq += diff * diff
      sumSq / samples.size

  /** Linear evaluator: Σ (count_f × weight_f). Missing weights treat
    * as 0 so additional feature keys in the sample are silently
    * ignored — the tuner only optimises features the caller chose to
    * initialise. */
  private[train] def evaluate(
      features: Map[String, Int],
      weights: Map[String, Int],
  ): Int =
    var sum = 0
    val it = features.iterator
    while it.hasNext do
      val (k, v) = it.next()
      sum += v * weights.getOrElse(k, 0)
    sum

  /** Standard logistic sigmoid. */
  private[train] def sigmoid(x: Double): Double =
    1.0 / (1.0 + math.exp(-x))

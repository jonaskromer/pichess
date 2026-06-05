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
    * @param weight  source-quality multiplier — the loss contribution
    *                is `weight × (predicted - outcome)²`, so a row
    *                from a high-quality corpus (e.g. PGN Mentor at
    *                1.0) pulls the optimum ~3× harder than a Lichess
    *                row at 0.3. Weighted MSE preserves the closed-
    *                form optimum at predicted = outcome per-row, so
    *                introducing the weight doesn't bias the result
    *                — it just emphasises the rows we trust more.
    */
  final case class Sample(
      features: Map[String, Double],
      outcome: Double,
      weight: Double = 1.0,
  )

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
      samples: IterableOnce[Sample],
      initial: Map[String, Int],
      K: Double = 0.4,
      maxIterations: Int = 100,
      initialStep: Int = 16,
  ): TuningResult =
    // Stream samples into the CompiledCorpus single-pass — the
    // caller can hand us a giant `Iterator[Sample]` (millions of
    // entries) without materializing the whole sequence in memory.
    // CompiledCorpus's per-feature growable arrays are the only
    // intermediate storage; the per-sample `Map[String, Double]`
    // is discarded after each `add` call.
    val corpus = CompiledCorpus.build(samples, initial)
    if corpus.sampleCount == 0 then TuningResult(initial, 0.0, 0)
    else
      runCachedCoordinateDescent(corpus, K, maxIterations, initialStep)
      corpus.toResult(initial)

  /** Same coordinate descent as the reference [[oneSweep]] loop, but
    * driven against a [[CompiledCorpus]] that:
    *   - stores features as parallel `Array[Int]` + `Array[Double]`
    *     (no `Map[String, _]` lookup in the inner loop),
    *   - keeps per-sample `cachedEval` + `cachedDiff` so that
    *     perturbing one weight only re-evaluates samples that
    *     actually carry that feature (O(featureToSamples(f).length)
    *     per trial, not O(n × featureCount)),
    *   - aggregates the weighted SSE via incremental deltas
    *     (`newSumSq = cachedSumSq − oldContrib + newContrib`).
    *
    * The math is identical to the reference path — see
    * [[CompiledCorpus]] doc — and equivalence is pinned by
    * `TexelTunerSpec.cached vs reference` (the new path's
    * [[TuningResult]] matches a hand-rolled fold over [[oneSweep]]).
    *
    * Floating-point drift from the incremental sumSq aggregation is
    * capped by a full [[CompiledCorpus.rebuildCache]] at the start of
    * each outer iteration (cheap — one inverted-index pass).
    */
  private def runCachedCoordinateDescent(
      corpus: CompiledCorpus,
      K: Double,
      maxIterations: Int,
      initialStep: Int,
  ): Unit =
    var step  = initialStep
    var iters = 0
    corpus.rebuildCache(K)
    var loss = corpus.currentLoss
    while step >= 1 && iters < maxIterations do
      iters += 1
      // Refresh from authoritative weights → bounds FP drift per
      // outer iteration. With sparse features this costs roughly the
      // same as a single oneSweep trial, so the safety-vs-perf
      // trade-off is negligible.
      corpus.rebuildCache(K)
      loss = corpus.currentLoss
      val startLoss = loss
      var f = 0
      while f < corpus.featureCount do
        val plusLoss  = corpus.trialLoss(f, +step, K)
        val minusLoss = corpus.trialLoss(f, -step, K)
        if plusLoss < loss && plusLoss <= minusLoss then
          corpus.commit(f, +step, K)
          loss = plusLoss
        else if minusLoss < loss then
          corpus.commit(f, -step, K)
          loss = minusLoss
        f += 1
      if loss >= startLoss then
        // No weight moved this sweep — halve the step and retry.
        step = step / 2
    corpus.iterations = iters
    // Final clean recompute so the published finalLoss is free of
    // any accumulated drift from the incremental sumSq path.
    corpus.rebuildCache(K)

  /** Single coordinate-descent sweep: try each weight ± step. */
  private[chess] def oneSweep(
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

  /** Sample-weight-aware mean squared sigmoid-mapped error.
    *
    * Each row contributes `weight × (predicted - actual)²` to the
    * numerator and `weight` to the denominator — so the result is a
    * proper weighted mean. Rows with weight 0 are silently ignored;
    * an all-zero sample set is treated as "no training data" and
    * returns 0 (a no-op for the tuner). */
  private[chess] def totalLoss(
      samples: IterableOnce[Sample],
      weights: Map[String, Int],
      K: Double,
  ): Double =
    var sumSq       = 0.0
    var sumWeights  = 0.0
    val it = samples.iterator
    while it.hasNext do
      val s = it.next()
      val predicted = sigmoid(K * evaluate(s.features, weights))
      val diff      = predicted - s.outcome
      sumSq      += s.weight * diff * diff
      sumWeights += s.weight
    if sumWeights == 0.0 then 0.0 else sumSq / sumWeights

  /** Linear evaluator: Σ (count_f × weight_f). Features carry
    * `Double` values (the tapered extractor scales by game phase so
    * integers wouldn't suffice); weights remain `Int` centipawns.
    * Missing weights are treated as 0 so additional feature keys in
    * the sample are silently ignored — the tuner only optimises
    * features the caller chose to initialise. */
  private[chess] def evaluate(
      features: Map[String, Double],
      weights: Map[String, Int],
  ): Double =
    var sum = 0.0
    val it = features.iterator
    while it.hasNext do
      val (k, v) = it.next()
      sum += v * weights.getOrElse(k, 0)
    sum

  /** Standard logistic sigmoid. */
  private[chess] def sigmoid(x: Double): Double =
    1.0 / (1.0 + math.exp(-x))

  /** Compiled, array-backed view of the training corpus used by the
    * fast [[tune]] path.
    *
    * The reference [[Sample]] / `Map[String, Double]` shape is fine
    * for callers + tests but pays a HashMap lookup per feature per
    * sample per trial — for a 690-feature tapered run with 1M
    * samples that's 1.4 *billion* `String.hashCode` calls per
    * coordinate-descent sweep, which is the dominant cost.
    *
    * `CompiledCorpus` flattens samples into parallel primitive
    * arrays plus an inverted index `featureToSamples(f) →
    * Array[Int]` of which samples reference feature `f`. The inner
    * loop becomes:
    *   - array indexing only,
    *   - per-trial cost proportional to the number of samples that
    *     actually carry feature `f` (sparse), not all samples,
    *   - zero allocation during tuning (apart from the result
    *     conversion at the end).
    *
    * Mutable state (`weights`, `cachedEval`, `cachedDiff`,
    * `cachedSumSq`, `iterations`) lives inside the compiled corpus
    * so the outer tuning loop is a plain mutator + accessor over a
    * single object. Not thread-safe — `tune` is sequential.
    */
  private[chess] final class CompiledCorpus(
      val featureCount: Int,
      val sampleCount: Int,
      // Canonical feature ordering: index → key name.
      val keys: Array[String],
      // Inverted index. For each feature f, the indices of samples
      // that carry it (`featureToSamples(f)`) and the corresponding
      // feature values (`featureToValues(f)`). Same length per pair.
      val featureToSamples: Array[Array[Int]],
      val featureToValues:  Array[Array[Double]],
      val outcomes:         Array[Double],
      val sampleWeights:    Array[Double],
      val totalSampleWeight: Double,
  ):

    val weights:    Array[Int]    = Array.ofDim[Int](featureCount)
    val cachedEval: Array[Double] = Array.ofDim[Double](sampleCount)
    val cachedDiff: Array[Double] = Array.ofDim[Double](sampleCount)
    var cachedSumSq: Double = 0.0
    var iterations:  Int    = 0

    /** Current weighted MSE under the cached state. */
    def currentLoss: Double =
      if totalSampleWeight == 0.0 then 0.0 else cachedSumSq / totalSampleWeight

    /** Recompute `cachedEval`, `cachedDiff`, `cachedSumSq` from the
      * authoritative `weights`. Idempotent — used to seed the cache
      * before the first sweep AND to cap floating-point drift between
      * outer iterations. Costs O(Σ_f |featureToSamples(f)|) +
      * O(sampleCount). */
    def rebuildCache(K: Double): Unit =
      java.util.Arrays.fill(cachedEval, 0.0)
      var f = 0
      while f < featureCount do
        val w = weights(f)
        if w != 0 then
          val idxs = featureToSamples(f)
          val vals = featureToValues(f)
          val len  = idxs.length
          var j = 0
          while j < len do
            cachedEval(idxs(j)) += vals(j) * w
            j += 1
        f += 1
      var s = 0.0
      var i = 0
      while i < sampleCount do
        val pred = sigmoid(K * cachedEval(i))
        val d    = pred - outcomes(i)
        cachedDiff(i) = d
        s += sampleWeights(i) * d * d
        i += 1
      cachedSumSq = s

    /** Loss if `weights(f)` were perturbed by `delta`. Does NOT
      * mutate the cache. */
    def trialLoss(f: Int, delta: Int, K: Double): Double =
      val idxs = featureToSamples(f)
      val vals = featureToValues(f)
      val len  = idxs.length
      var sumSqDelta = 0.0
      var j = 0
      while j < len do
        val i      = idxs(j)
        val newEval = cachedEval(i) + delta * vals(j)
        val newPred = sigmoid(K * newEval)
        val newDiff = newPred - outcomes(i)
        val oldDiff = cachedDiff(i)
        sumSqDelta += sampleWeights(i) * (newDiff * newDiff - oldDiff * oldDiff)
        j += 1
      val newSumSq = cachedSumSq + sumSqDelta
      if totalSampleWeight == 0.0 then 0.0 else newSumSq / totalSampleWeight

    /** Apply the perturbation: update `weights(f)`, `cachedEval`,
      * `cachedDiff`, `cachedSumSq` to reflect the new state.
      * Caller is responsible for having verified that the trial
      * improves loss. */
    def commit(f: Int, delta: Int, K: Double): Unit =
      val idxs = featureToSamples(f)
      val vals = featureToValues(f)
      val len  = idxs.length
      var sumSqDelta = 0.0
      var j = 0
      while j < len do
        val i      = idxs(j)
        val newEval = cachedEval(i) + delta * vals(j)
        val newPred = sigmoid(K * newEval)
        val newDiff = newPred - outcomes(i)
        val oldDiff = cachedDiff(i)
        sumSqDelta   += sampleWeights(i) * (newDiff * newDiff - oldDiff * oldDiff)
        cachedEval(i) = newEval
        cachedDiff(i) = newDiff
        j += 1
      weights(f) += delta
      cachedSumSq += sumSqDelta

    /** Project the array-backed state back into the public API: a
      * `TuningResult` with `Map[String, Int]` weights. The `initial`
      * vector is passed through so any keys we didn't carry in
      * `keys` (shouldn't happen if `build` was called with the same
      * initial, but defensive) round-trip unchanged. */
    def toResult(initial: Map[String, Int]): TuningResult =
      var out = initial
      var i = 0
      while i < featureCount do
        out = out.updated(keys(i), weights(i))
        i += 1
      TuningResult(out, currentLoss, iterations)

  private[chess] object CompiledCorpus:

    /** Compile any `IterableOnce[Sample]` into the flat
      * representation in a SINGLE pass over the input — the
      * caller can stream millions of samples through an iterator
      * (e.g. from a SQL cursor) without materialising the whole
      * collection in memory.
      *
      * Internally uses per-feature `ArrayBuilder` so the inverted
      * index grows incrementally (amortised O(1) per sample).
      * Outcomes + per-sample weights also grow via builders.
      *
      * Keys absent from `initial` are silently dropped (matches
      * the reference [[evaluate]] semantics — only adjustable
      * features are tracked). */
    def build(samples: IterableOnce[Sample], initial: Map[String, Int]): CompiledCorpus =
      val keys: Array[String] = initial.keys.toArray
      val featureCount = keys.length
      val keyToIdx: Map[String, Int] = keys.iterator.zipWithIndex.toMap

      // Per-feature growable inverted-index builders. Indexed by
      // feature id; entries are pushed as samples flow in.
      val idxBuilders: Array[scala.collection.mutable.ArrayBuilder[Int]] =
        Array.fill(featureCount)(Array.newBuilder[Int])
      val valBuilders: Array[scala.collection.mutable.ArrayBuilder[Double]] =
        Array.fill(featureCount)(Array.newBuilder[Double])
      val outcomeBuilder      = Array.newBuilder[Double]
      val sampleWeightBuilder = Array.newBuilder[Double]

      var sampleIdx       = 0
      var totalSampleWeight = 0.0
      val it = samples.iterator
      while it.hasNext do
        val s = it.next()
        outcomeBuilder      += s.outcome
        sampleWeightBuilder += s.weight
        totalSampleWeight   += s.weight
        s.features.foreach { case (k, v) =>
          if v != 0.0 then
            keyToIdx.get(k) match
              case Some(idx) =>
                idxBuilders(idx) += sampleIdx
                valBuilders(idx) += v
              case None => // not adjustable; drop
        }
        sampleIdx += 1

      val n = sampleIdx
      val featureToSamples = Array.ofDim[Array[Int]](featureCount)
      val featureToValues  = Array.ofDim[Array[Double]](featureCount)
      var f = 0
      while f < featureCount do
        featureToSamples(f) = idxBuilders(f).result()
        featureToValues(f)  = valBuilders(f).result()
        f += 1

      val outcomes      = outcomeBuilder.result()
      val sampleWeights = sampleWeightBuilder.result()

      val corpus = new CompiledCorpus(
        featureCount, n, keys,
        featureToSamples, featureToValues,
        outcomes, sampleWeights, totalSampleWeight,
      )
      // Initialise corpus.weights from initial. Done after construction
      // so all immutable fields are set first.
      var k = 0
      while k < featureCount do
        corpus.weights(k) = initial(keys(k))
        k += 1
      corpus

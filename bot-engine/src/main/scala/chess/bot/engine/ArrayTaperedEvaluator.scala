package chess.bot.engine

import chess.bot.engine.FeatureExtractor.{FeatureIndex, FullFeatures}
import chess.model.board.{GameState, PositionView}

/** Zero-allocation tapered evaluator for the search hot path.
  *
  * Same scoring formula as [[TaperedEvaluator]] — phase-weighted blend of mg
  * and eg weight tables — but designed to be called thousands of times per
  * [[Search.bestMove]] without allocating:
  *
  *   - Features land in a reusable `Array[Int]` (indexed by [[FeatureIndex]]),
  *     filled by [[FullFeatures.fillArray]]. No `Map[String, Int]` constructed
  *     per call, no String concat per feature.
  *   - Weights are pre-projected into two parallel `Array[Int]` (`mgWeights`,
  *     `egWeights`) at construction time. Lookup is an array index, not a
  *     `Map.get` + suffix concat.
  *   - The eval loop is a single while over featureCount with one multiply +
  *     one add per `_mg`/`_eg` pair.
  *
  * The reusable buffer is held inside the evaluator — single- threaded by
  * construction. Search is sequential per node so the shared buffer is safe; a
  * future multi-threaded search would give each thread its own evaluator.
  *
  * Score equivalence with [[TaperedEvaluator]] is pinned by a specs check: same
  * weights + position → identical Int score (the rounding happens in the same
  * place, so no FP-vs-integer mismatch).
  */
final class ArrayTaperedEvaluator private (
    mgWeights: Array[Int],
    egWeights: Array[Int]
) extends Evaluator:

  // Reused feature buffer, one per thread. Cleared at the start of
  // every fill. ThreadLocal so the evaluator is safe to SHARE across
  // concurrently-evaluating threads (parallel data-gen / tournaments)
  // — a single shared buffer would be raced (corrupt features →
  // garbage evals). The production search is single-threaded, so the
  // per-call `ThreadLocal.get()` is the cheap same-thread fast path
  // (~1 ns), negligible against a full search.
  private val featureBuf: ThreadLocal[Array[Int]] =
    ThreadLocal.withInitial(() => new Array[Int](FeatureIndex.Count))

  def evaluate(state: PositionView): Int =
    val featureBuf = this.featureBuf.get()
    FullFeatures.fillArray(state, featureBuf)
    val phase = GamePhase.compute(state.board)
    var mgSum = 0
    var egSum = 0
    var i = 0
    val n = featureBuf.length
    while i < n do
      val v = featureBuf(i)
      if v != 0 then
        mgSum += v * mgWeights(i)
        egSum += v * egWeights(i)
      i += 1
    math.round(phase * mgSum + (1.0 - phase) * egSum).toInt

  /** Decomposed eval per feature group. Same blend formula as `evaluate`, but
    * the mg/eg sums are bucketed by which slice of the feature vector
    * contributed. Used by NnueDataGen to emit a `comps:` column so future NNUE
    * training can have a component-bucketed multi-head output
    * (Stockfish-style).
    *
    * Groups: material — piece-count features (Pawn..Queen) pst —
    * piece-square-table features (5 × 64 slots) mobility —
    * knight/bishop/rook/queen mobility pawn_struct — passed / isolated /
    * doubled / connected king_safety — pawn shield + attackers rook — open /
    * semi-open file bonuses misc — bishop pair, knight outpost, tempo
    *
    * Sum of all component values equals the total `evaluate(state)`.
    */
  override def evaluateComponents(state: GameState): Map[String, Int] =
    val featureBuf = this.featureBuf.get()
    FullFeatures.fillArray(state, featureBuf)
    val phase = GamePhase.compute(state.board)

    val materialIdxs = (FeatureIndex.Pawn to FeatureIndex.Queen)
    val pstIdxs =
      FeatureIndex.PstPawnBase until (FeatureIndex.PstQueenBase + 64)
    val mobilityIdxs = FeatureIndex.KnightMob to FeatureIndex.QueenMob
    val pawnStructIdxs =
      (FeatureIndex.PassedRankBase until FeatureIndex.PassedRankBase + 6) ++
        Seq(
          FeatureIndex.IsolatedPawn,
          FeatureIndex.DoubledPawn,
          FeatureIndex.ConnectedPawn
        )
    val kingSafetyIdxs =
      Seq(FeatureIndex.PawnShield, FeatureIndex.KingAttackers)
    val rookIdxs = Seq(FeatureIndex.RookOpenFile, FeatureIndex.RookSemiOpenFile)
    val miscIdxs =
      Seq(
        FeatureIndex.BishopPair,
        FeatureIndex.KnightOutpost,
        FeatureIndex.Tempo
      )

    def sumBlend(idxs: Seq[Int]): Int =
      var mg = 0
      var eg = 0
      idxs.foreach { i =>
        val v = featureBuf(i)
        if v != 0 then
          mg += v * mgWeights(i)
          eg += v * egWeights(i)
      }
      math.round(phase * mg + (1.0 - phase) * eg).toInt

    Map(
      "mat" -> sumBlend(materialIdxs),
      "pst" -> sumBlend(pstIdxs),
      "mob" -> sumBlend(mobilityIdxs),
      "ps" -> sumBlend(pawnStructIdxs),
      "ks" -> sumBlend(kingSafetyIdxs),
      "rook" -> sumBlend(rookIdxs),
      "misc" -> sumBlend(miscIdxs)
    )

object ArrayTaperedEvaluator:

  /** Project a `Map[String, Int]` weights snapshot into the two parallel arrays
    * the evaluator expects. The same `_mg` / `_eg` fallback logic as
    * [[TaperedEvaluator]]: when a suffixed key is absent the un-suffixed key is
    * used (so legacy `v1.json` snapshots still play), and missing keys fall
    * back to 0.
    *
    * The projection is the only place where the `Map[String, Int]` →
    * `Array[Int]` translation happens. Build it once at evaluator construction;
    * the hot path then only reads arrays.
    */
  def apply(weights: Map[String, Int]): ArrayTaperedEvaluator =
    val mg = new Array[Int](FeatureIndex.Count)
    val eg = new Array[Int](FeatureIndex.Count)
    var i = 0
    while i < FeatureIndex.Count do
      val key = FeatureIndex.keyByIdx(i)
      mg(i) = lookupWeight(weights, key, mg = true)
      eg(i) = lookupWeight(weights, key, mg = false)
      i += 1
    new ArrayTaperedEvaluator(mg, eg)

  /** Same two-step lookup as [[TaperedEvaluator]]'s `weight` helper: prefer the
    * suffixed key, fall back to the un-suffixed key for legacy/non-tapered
    * snapshots, then 0.
    */
  private def lookupWeight(
      weights: Map[String, Int],
      key: String,
      mg: Boolean
  ): Int =
    val suffix = if mg then "_mg" else "_eg"
    weights.get(s"${key}${suffix}").orElse(weights.get(key)).getOrElse(0)

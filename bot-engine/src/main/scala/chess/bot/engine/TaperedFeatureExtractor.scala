package chess.bot.engine

import chess.model.board.GameState

/** Same role as [[FeatureExtractor]], but emits the doubled
  * tapered-eval feature space: every key `foo` from the underlying
  * Int extractor becomes two phase-scaled `Double` keys —
  * `foo_mg` (opening/middlegame contribution) and `foo_eg` (endgame
  * contribution).
  *
  * For a feature with raw integer count `v` and game-phase
  * `p ∈ [0, 1]` (1 = opening):
  *
  * {{{
  *   foo_mg = v * p
  *   foo_eg = v * (1 - p)
  * }}}
  *
  * The Texel tuner is then a plain dot product over the `Double`
  * features and `Int` (centipawn) weights — no special handling of
  * phase, the linearity is baked into the feature representation.
  * That's the whole point of expressing tapered eval this way: the
  * tuner stays a generic linear-regression machine.
  */
trait TaperedFeatureExtractor:
  def features(state: GameState): Map[String, Double]

object TaperedFeatureExtractor:

  /** Tapered version of [[FeatureExtractor.full]]. ~690 features. */
  val full: TaperedFeatureExtractor =
    over(FeatureExtractor.full)

  /** Generic tapered wrapper over any Int-valued [[FeatureExtractor]].
    * The phase is computed from the board state once per call. */
  def over(base: FeatureExtractor): TaperedFeatureExtractor =
    new TaperedFeatureExtractor:
      def features(state: GameState): Map[String, Double] =
        val raw   = base.features(state)
        val phase = GamePhase.compute(state)
        val mgF   = phase
        val egF   = 1.0 - phase
        raw.flatMap { case (k, v) =>
          val vd = v.toDouble
          Seq(s"${k}_mg" -> vd * mgF, s"${k}_eg" -> vd * egF)
        }

  /** Every key the [[full]] extractor can emit, in canonical order.
    * Each underlying feature contributes two entries (`_mg` + `_eg`). */
  def allFeatureNames: Seq[String] =
    FeatureExtractor.allFeatureNames.flatMap(k => Seq(s"${k}_mg", s"${k}_eg"))

  /** Default seed weights for the tapered tuner: every feature key
    * starts at 0 except material, which gets canonical centipawn
    * values (`pawn = 100`, `knight = 320`, etc.) in both `_mg` and
    * `_eg` slots. The tuner then nudges each weight; PSTs typically
    * converge to ±5–30 cp, mobility to ~3–10 cp per move, etc.
    *
    * Use as the `initial` argument to
    * `CorpusTrainer.tuneAndPersist` for a fresh training run on a
    * corpus that doesn't have prior tapered weights. To resume from
    * a previous run, prefer loading the snapshot via
    * [[chess.bot.engine.WeightsLoader]] and falling back to this. */
  def defaultSeedWeights: Map[String, Int] =
    val all = allFeatureNames.map(_ -> 0).toMap
    val materialSeeds = Seq(
      "pawn"   -> 100,
      "knight" -> 320,
      "bishop" -> 330,
      "rook"   -> 500,
      "queen"  -> 900,
    ).flatMap { case (k, v) => Seq(s"${k}_mg" -> v, s"${k}_eg" -> v) }.toMap
    all ++ materialSeeds

  /** Promote a legacy un-tapered weight snapshot (e.g. `v1.json`
    * with just `pawn → 100`) to a tapered one by duplicating each
    * weight into its `_mg` / `_eg` variants. Used by `TrainMain`
    * when the previous snapshot pre-dates tapered eval — the tuner
    * then has a non-zero starting point for material and refines
    * from there. Tapered keys already present in the input are
    * preserved unchanged. */
  def promoteToTapered(weights: Map[String, Int]): Map[String, Int] =
    val builder = Map.newBuilder[String, Int]
    weights.foreach {
      case (k, _) if k.endsWith("_mg") || k.endsWith("_eg") =>
        builder += (k -> weights(k))
      case (k, v) =>
        // Only inject the _mg/_eg pair if there isn't already a
        // tapered entry to avoid clobbering a partial tapered snapshot.
        if !weights.contains(s"${k}_mg") then builder += (s"${k}_mg" -> v)
        if !weights.contains(s"${k}_eg") then builder += (s"${k}_eg" -> v)
    }
    builder.result()

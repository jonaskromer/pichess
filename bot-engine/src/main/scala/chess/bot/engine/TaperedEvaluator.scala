package chess.bot.engine

import chess.model.board.GameState

/** Tapered version of [[TunedEvaluator]] — uses two weight tables
  * keyed by `_mg` / `_eg` suffixes and blends them by the position's
  * game phase.
  *
  * Sticks to Int-valued features at runtime (one extractor call per
  * eval, same as [[TunedEvaluator]]) and reads two weights per
  * feature, avoiding the doubled-map traversal that the tapered
  * tuner uses internally. This keeps search hot-loop cost flat: it's
  * the same per-eval shape as the non-tapered evaluator, just with
  * two weight lookups instead of one.
  *
  * Backward compatibility: when a `_mg` or `_eg` key is missing from
  * `weights`, the lookup falls through to the unsuffixed key (e.g.
  * `pawn` without a suffix). So a legacy `v1.json` / `v2.json` that
  * pre-dates tapered eval is still usable as-is — both mg and eg
  * branches pick up the same un-suffixed weight, and the blend
  * collapses to `weight * count`, exactly the old behaviour. The
  * engine plays the same with old weights and starts using the
  * tapered surface as soon as a tuned `_mg` / `_eg` snapshot lands.
  */
object TaperedEvaluator:

  /** Construct a tapered evaluator over the given weight map and
    * underlying (Int) feature extractor — usually
    * [[FeatureExtractor.full]]. */
  def apply(
      weights: Map[String, Int],
      extractor: FeatureExtractor,
  ): Evaluator =
    new Evaluator:
      def evaluate(state: GameState): Int =
        val features = extractor.features(state)
        val phase    = GamePhase.compute(state.board)
        var mgSum    = 0
        var egSum    = 0
        val it = features.iterator
        while it.hasNext do
          val (k, v) = it.next()
          mgSum += v * weight(weights, k, mg = true)
          egSum += v * weight(weights, k, mg = false)
        // phase = 1 → fully opening; phase = 0 → fully endgame.
        // mgSum applies at the opening end of the spectrum; egSum at
        // the endgame end. Round to Int — caller treats centipawns.
        math.round(phase * mgSum + (1.0 - phase) * egSum).toInt

  /** Look up either the `_mg` / `_eg` variant of `key` in `weights`,
    * falling back to the un-suffixed key (legacy / non-tapered
    * snapshots) and finally 0. The two-step lookup is what lets old
    * weight files coexist with tapered weights. */
  private inline def weight(
      weights: Map[String, Int],
      key: String,
      mg: Boolean,
  ): Int =
    val suffix = if mg then "_mg" else "_eg"
    weights.get(s"${key}${suffix}").orElse(weights.get(key)).getOrElse(0)

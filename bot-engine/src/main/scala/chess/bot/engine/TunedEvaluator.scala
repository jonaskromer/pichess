package chess.bot.engine

import chess.model.board.PositionView

/** Evaluator that combines a [[FeatureExtractor]] with a tuned weight vector.
  * The output is `Σ (feature_count × weight)` in centipawns, white-side
  * perspective — the same shape as [[MaterialEvaluator]]'s result, just with
  * weights provided at runtime instead of compiled-in.
  *
  * This is what the Texel-tuner pipeline writes to: train the weights offline →
  * ship them as JSON or via [[chess.bot.data.WeightsRepo]] → load on bot
  * startup → wrap into a [[TunedEvaluator]] → hand it to [[Search.alphaBeta]].
  * The engine doesn't care how the weights were produced.
  */
object TunedEvaluator:

  /** Construct an evaluator over the given weights and extractor. */
  def apply(weights: Map[String, Int], extractor: FeatureExtractor): Evaluator =
    new Evaluator:
      def evaluate(state: PositionView): Int =
        val features = extractor.features(state)
        var sum = 0
        val it = features.iterator
        while it.hasNext do
          val (k, v) = it.next()
          sum += v * weights.getOrElse(k, 0)
        sum

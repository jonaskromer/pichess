package chess.bot.engine

import zio.json.*

/** A versioned, committable snapshot of the evaluator's weights.
  *
  * `weights` maps feature name → centipawn coefficient. Feature names match
  * what [[FeatureExtractor]] emits — keys not present in the snapshot are
  * silently treated as zero by [[TunedEvaluator]], so the snapshot can lag the
  * extractor without breaking startup (an "older weights" file just plays a
  * weaker version of the engine, not a broken one).
  *
  * Versioning lets us ship multiple snapshots side-by-side in the
  * `bot-engine/resources/weights/` directory: `v1.json` is the seed
  * (material-only constants), each successful tuner run drops a `vN+1.json`
  * next to it, and the engine picks the highest version via
  * [[WeightsLoader.loadLatest]] (or a specific one via [[WeightsLoader.load]]
  * for rollback / A-B testing).
  */
final case class WeightSnapshot(
    version: Int,
    weights: Map[String, Int]
)

object WeightSnapshot:

  /** zio-json codecs. The on-disk shape is a plain `{ version, weights }`
    * object with weights as a string→int map — kept dead simple so the file is
    * readable + editable by hand if needed. See
    * `bot-engine/src/main/resources/weights/v1.json` for the seed example.
    */
  given JsonEncoder[WeightSnapshot] = DeriveJsonEncoder.gen[WeightSnapshot]
  given JsonDecoder[WeightSnapshot] = DeriveJsonDecoder.gen[WeightSnapshot]

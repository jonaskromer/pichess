package chess.bot.engine

import chess.model.board.GameState

/** Maps a [[GameState]] to a `Map[String, Int]` feature vector that
  * [[TunedEvaluator]] uses for its linear combination.
  *
  * The Phase 5 extractor counts piece-type differences (white minus
  * black) — same feature space as the hand-coded
  * [[MaterialEvaluator]], so Texel tuning over [[features]] is
  * directly comparable to that evaluator's hardcoded constants.
  * Later phases will fold in piece-square tables (one feature per
  * piece × square), mobility, pawn structure, etc., by adding new
  * extractors or extending this one.
  */
trait FeatureExtractor:
  def features(state: GameState): Map[String, Int]

object FeatureExtractor:

  /** Feature names emitted by [[material]]. Useful as the default
    * key set when initialising a [[TexelTuner]]-equivalent weight
    * vector. */
  val materialNames: List[String] =
    List("pawn", "knight", "bishop", "rook", "queen")

  /** Material-only extractor: each feature is (white count) − (black
    * count) for that piece type. The king is omitted — its count is
    * always one per side and contributes nothing to the linear
    * combination. */
  val material: FeatureExtractor = MaterialFeatures

  private object MaterialFeatures extends FeatureExtractor:
    def features(state: GameState): Map[String, Int] =
      val b = state.board
      Map(
        "pawn"   -> (b.pawnsW.popCount   - b.pawnsB.popCount),
        "knight" -> (b.knightsW.popCount - b.knightsB.popCount),
        "bishop" -> (b.bishopsW.popCount - b.bishopsB.popCount),
        "rook"   -> (b.rooksW.popCount   - b.rooksB.popCount),
        "queen"  -> (b.queensW.popCount  - b.queensB.popCount),
      )

package chess.bot.tournament

/** piChess's self-description for the NowChess bot registry (`POST /api/bots`).
  *
  * Registered under our own auth token, so the registry entry is keyed by the
  * same id the tournament uses for our participation — which means these fields
  * surface in the server's `analytics-export` for every game we play and in the
  * standings (the registry is where the export reads bot metadata from).
  */
final case class BotMetadata(
    family: String,
    strategyType: String,
    engineType: String,
    modelVersion: String
)

object BotMetadata:

  /** piChess's identity: an alpha-beta searcher with the NNUE+HCE hybrid eval.
    * `modelVersion` pins the HCE weights snapshot + the NNUE net so an archived
    * game records which engine version produced it.
    */
  def pichess(weightsVersion: Int): BotMetadata =
    BotMetadata(
      family = "piChess",
      strategyType = "alpha-beta",
      engineType = "NNUE+HCE hybrid",
      modelVersion = s"weights-v$weightsVersion+nnue-v1"
    )

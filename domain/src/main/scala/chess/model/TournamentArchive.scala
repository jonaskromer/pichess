package chess.model

/** One bot's final standing in an archived tournament — the ladder row. Carries
  * the bot's registry metadata (family / engine / model) so the browse view can
  * show what kind of engine each competitor was. */
final case class TournamentStanding(
    rank: Int,
    botId: String,
    botName: String,
    family: Option[String],
    engineType: Option[String],
    modelVersion: Option[String],
    points: Double,
    wins: Int,
    draws: Int,
    losses: Int,
    tieBreak: Double
)

/** A finished tournament captured for post-tournament browsing + analysis: its
  * metadata, the final ladder, and the ids of every game played (each game's
  * moves live in the per-game [[GameArchive]] store, keyed by these ids). */
final case class TournamentArchive(
    tournamentId: String,
    name: String,
    format: String,
    finishedAt: Long,
    standings: List[TournamentStanding],
    gameIds: List[String]
)

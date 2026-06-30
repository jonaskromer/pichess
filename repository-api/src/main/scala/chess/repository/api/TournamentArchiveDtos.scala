package chess.repository.api

import zio.json.*

/** One ladder row of an archived tournament (mirrors `chess.model.TournamentStanding`). */
final case class TournamentStandingDto(
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
object TournamentStandingDto:
  given JsonCodec[TournamentStandingDto] =
    DeriveJsonCodec.gen[TournamentStandingDto]

/** A finished tournament: ingest body (`POST /tournament-archives`) AND detail
  * response (`GET /tournament-archives/{id}`). `gameIds` reference per-game
  * archives in the game store. */
final case class TournamentArchiveDto(
    tournamentId: String,
    name: String,
    format: String,
    finishedAt: Long,
    standings: List[TournamentStandingDto],
    gameIds: List[String]
)
object TournamentArchiveDto:
  given JsonCodec[TournamentArchiveDto] =
    DeriveJsonCodec.gen[TournamentArchiveDto]

/** Lightweight row for the history index (`GET /tournament-archives`). */
final case class TournamentSummaryDto(
    tournamentId: String,
    name: String,
    format: String,
    finishedAt: Long,
    nbPlayers: Int,
    winner: Option[String]
)
object TournamentSummaryDto:
  given JsonCodec[TournamentSummaryDto] =
    DeriveJsonCodec.gen[TournamentSummaryDto]

package chess.repository.api

import zio.json.*

/** One submitted half-move: the UCI move plus the side's remaining clock after
  * it (and optionally the elapsed move time). SAN + resulting FEN are derived
  * server-side by replaying the UCI, so the submitter needs no board context.
  */
final case class SubmittedMoveDto(
    uci: String,
    clockMs: Option[Long],
    emtMs: Option[Long]
)
object SubmittedMoveDto:
  given JsonCodec[SubmittedMoveDto] = DeriveJsonCodec.gen[SubmittedMoveDto]

/** A finished game submitted for archiving (e.g. a tournament game the bot
  * played off the Kafka path). The repository replays the UCI moves to build the
  * full archive (SAN, opening, PGN-with-clocks) and persists it.
  */
final case class ArchiveSubmissionDto(
    gameId: String,
    source: String,
    white: String,
    black: String,
    result: String,
    timeControl: Option[String],
    moves: List[SubmittedMoveDto]
)
object ArchiveSubmissionDto:
  given JsonCodec[ArchiveSubmissionDto] = DeriveJsonCodec.gen[ArchiveSubmissionDto]

/** An archived game served back for review / analysis: the PGN-with-clocks plus
  * a little metadata. The client feeds `pgn` to `POST /api/analyze`.
  */
final case class ArchivePgnDto(
    pgn: String,
    white: String,
    black: String,
    result: String,
    opening: String
)
object ArchivePgnDto:
  given JsonCodec[ArchivePgnDto] = DeriveJsonCodec.gen[ArchivePgnDto]

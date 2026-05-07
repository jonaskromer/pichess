package chess.persistence.postgres

import chess.model.{GameId, InviteCode, LobbyId}
import slick.jdbc.PostgresProfile.api.*

import java.time.Instant

/** Slick table definitions for the games and lobbies relations.
  *
  * State is stored as FEN (for games) or columns (for lobbies). Both tables
  * carry an `updated_at` column so a future replication / debugging consumer
  * can order rows by last-write without reaching for a separate event log.
  */
object Tables:

  final case class GameRow(
      id: GameId,
      fen: String,
      updatedAt: Instant
  )

  final class GamesTable(tag: Tag)
      extends Table[GameRow](tag, "games"):
    def id: Rep[GameId] = column[GameId]("id", O.PrimaryKey)
    def fen: Rep[String] = column[String]("fen")
    def updatedAt: Rep[Instant] = column[Instant]("updated_at")

    def * = (id, fen, updatedAt).mapTo[GameRow]

  val games = TableQuery[GamesTable]

  final case class LobbyRow(
      id: LobbyId,
      inviteCode: String,
      hostNickname: String,
      guestNickname: Option[String],
      status: String,
      gameId: Option[GameId],
      createdAt: Long,
      updatedAt: Instant
  )

  final class LobbiesTable(tag: Tag)
      extends Table[LobbyRow](tag, "lobbies"):
    def id: Rep[LobbyId] = column[LobbyId]("id", O.PrimaryKey)
    def inviteCode: Rep[String] = column[String]("invite_code", O.Unique)
    def hostNickname: Rep[String] = column[String]("host_nickname")
    def guestNickname: Rep[Option[String]] =
      column[Option[String]]("guest_nickname")
    def status: Rep[String] = column[String]("status")
    def gameId: Rep[Option[GameId]] = column[Option[GameId]]("game_id")
    def createdAt: Rep[Long] = column[Long]("created_at")
    def updatedAt: Rep[Instant] = column[Instant]("updated_at")

    def * =
      (
        id,
        inviteCode,
        hostNickname,
        guestNickname,
        status,
        gameId,
        createdAt,
        updatedAt
      ).mapTo[LobbyRow]

    def inviteCodeIdx = index("lobbies_invite_code_idx", inviteCode, unique = true)

  val lobbies = TableQuery[LobbiesTable]

  /** DDL for both tables, used at service startup to ensure the schema exists.
    * Idempotent in the sense that we wrap creates with try/recover at call-
    * site so re-creating an existing table doesn't fail the boot.
    */
  val schema = games.schema ++ lobbies.schema

package chess.persistence.postgres

import chess.model.{
  InviteCode,
  Lobby,
  LobbyError,
  LobbyId,
  LobbyStatus,
  LobbyVisibility
}
import chess.persistence.LobbyRepository
import slick.jdbc.PostgresProfile.api.*
import zio.*

import java.time.Instant

final class PostgresLobbyRepository(db: PostgresDatabase) extends LobbyRepository:

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    db.run(Tables.lobbies += toRow(lobby)).unit.mapError(toInfraError)

  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    db.run(Tables.lobbies.filter(_.id === id).result.headOption)
      .mapError(toInfraError)
      .map(_.map(fromRow))

  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    db.run(
      Tables.lobbies.filter(_.inviteCode === code.value).result.headOption
    ).mapError(toInfraError)
      .map(_.map(fromRow))

  def update(lobby: Lobby): IO[LobbyError, Unit] =
    // Same atomicity caveat as PostgresGameRepository.save — Slick's
    // `insertOrUpdate` is not atomic under concurrent writes to the same
    // primary key. Hand-rolled `INSERT … ON CONFLICT DO UPDATE` is.
    val row = toRow(lobby)
    // sqlu needs a SetParameter for each interpolated value; Slick ships
    // one for `java.sql.Timestamp` but not for `java.time.Instant`.
    val updatedTs = java.sql.Timestamp.from(row.updatedAt)
    val gameIdOpt: Option[String] = row.gameId.map(identity)
    val upsert =
      sqlu"""
        INSERT INTO lobbies (
          id, invite_code,
          host_nickname, host_session_id,
          guest_nickname, guest_session_id,
          visibility, allow_undo, allow_spectate, spectator_limit,
          status, game_id, created_at, updated_at
        )
        VALUES (
          ${row.id}, ${row.inviteCode},
          ${row.hostNickname}, ${row.hostSessionId},
          ${row.guestNickname}, ${row.guestSessionId},
          ${row.visibility}, ${row.allowUndo}, ${row.allowSpectate}, ${row.spectatorLimit},
          ${row.status}, ${gameIdOpt}, ${row.createdAt}, ${updatedTs}
        )
        ON CONFLICT (id) DO UPDATE SET
          invite_code      = EXCLUDED.invite_code,
          host_nickname    = EXCLUDED.host_nickname,
          host_session_id  = EXCLUDED.host_session_id,
          guest_nickname   = EXCLUDED.guest_nickname,
          guest_session_id = EXCLUDED.guest_session_id,
          visibility       = EXCLUDED.visibility,
          allow_undo       = EXCLUDED.allow_undo,
          allow_spectate   = EXCLUDED.allow_spectate,
          spectator_limit  = EXCLUDED.spectator_limit,
          status           = EXCLUDED.status,
          game_id          = EXCLUDED.game_id,
          created_at       = EXCLUDED.created_at,
          updated_at       = EXCLUDED.updated_at
      """
    db.run(upsert).unit.mapError(toInfraError)

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    db.run(Tables.lobbies.filter(_.id === id).delete).unit
      .mapError(toInfraError)

  def listPublicWaiting(): IO[LobbyError, List[Lobby]] =
    db.run(
      Tables.lobbies
        .filter(l =>
          l.visibility === LobbyVisibility.Public.toString &&
            l.status === LobbyStatus.Waiting.toString
        )
        .sortBy(_.createdAt.asc)
        .result
    ).mapError(toInfraError)
      .map(_.toList.map(fromRow))

  private def toRow(lobby: Lobby): Tables.LobbyRow =
    Tables.LobbyRow(
      id = lobby.id,
      inviteCode = lobby.inviteCode.value,
      hostNickname = lobby.hostNickname,
      hostSessionId = lobby.hostSessionId,
      guestNickname = lobby.guestNickname,
      guestSessionId = lobby.guestSessionId,
      visibility = lobby.visibility.toString,
      allowUndo = lobby.allowUndo,
      allowSpectate = lobby.allowSpectate,
      spectatorLimit = lobby.spectatorLimit,
      status = lobby.status.toString,
      gameId = lobby.gameId,
      createdAt = lobby.createdAt,
      updatedAt = Instant.now
    )

  private def fromRow(row: Tables.LobbyRow): Lobby =
    Lobby(
      id = row.id,
      inviteCode = InviteCode.unsafe(row.inviteCode),
      hostNickname = row.hostNickname,
      hostSessionId = row.hostSessionId,
      guestNickname = row.guestNickname,
      guestSessionId = row.guestSessionId,
      visibility = parseVisibility(row.visibility),
      allowUndo = row.allowUndo,
      allowSpectate = row.allowSpectate,
      spectatorLimit = row.spectatorLimit,
      status = parseStatus(row.status),
      createdAt = row.createdAt,
      gameId = row.gameId
    )

  private def parseStatus(raw: String): LobbyStatus =
    LobbyStatus.values
      .find(_.toString == raw)
      .getOrElse(LobbyStatus.Closed)

  private def parseVisibility(raw: String): LobbyVisibility =
    LobbyVisibility.values
      .find(_.toString == raw)
      .getOrElse(LobbyVisibility.Private)

  private def toInfraError(t: Throwable): LobbyError =
    LobbyError.InfrastructureError(s"Postgres error: ${t.getMessage}")

object PostgresLobbyRepository:
  val layer: URLayer[PostgresDatabase, LobbyRepository] =
    ZLayer.fromFunction(PostgresLobbyRepository(_))

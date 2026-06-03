package chess.persistence.cassandra

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{BatchStatement, BatchType, Row}
import zio.*

import chess.model.{
  InviteCode,
  Lobby,
  LobbyError,
  LobbyId,
  LobbyStatus,
  LobbyVisibility
}
import chess.persistence.LobbyRepository

/** Cassandra-backed `LobbyRepository`. Two tables:
  *
  *   - `lobbies (lobby_id PK, ...)` — primary record
  *   - `lobbies_by_invite (invite_code PK, lobby_id)` — denormalised inverse
  *     for the join lookup
  *
  * Two-table writes are bundled into a logged batch so a partial-failure
  * doesn't leave the inverse table dangling.
  *
  * `listPublicWaiting` uses `ALLOW FILTERING` against the main table — an
  * acceptable dev-only shortcut at the scale of an interactive lobby
  * browser. A production deployment would maintain a third denormalised
  * `lobbies_public_waiting` table keyed by a composite partition and
  * synchronise it on every write.
  */
final class CassandraLobbyRepository(session: CqlSession) extends LobbyRepository:

  private val insertLobby = session.prepare("""
    INSERT INTO lobbies
      (lobby_id, invite_code, host_nickname, host_session_id,
       guest_nickname, guest_session_id, visibility, allow_undo,
       allow_spectate, spectator_limit, status, game_id, created_at,
       updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  """)
  private val insertInverse =
    session.prepare(
      "INSERT INTO lobbies_by_invite (invite_code, lobby_id) VALUES (?, ?)"
    )
  private val selectById =
    session.prepare("SELECT * FROM lobbies WHERE lobby_id = ?")
  private val selectInverse =
    session.prepare("SELECT lobby_id FROM lobbies_by_invite WHERE invite_code = ?")
  private val selectPublicWaiting =
    session.prepare(
      "SELECT * FROM lobbies WHERE visibility = ? AND status = ? ALLOW FILTERING"
    )
  private val deleteLobby =
    session.prepare("DELETE FROM lobbies WHERE lobby_id = ?")
  private val deleteInverse =
    session.prepare("DELETE FROM lobbies_by_invite WHERE invite_code = ?")

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    val batch = BatchStatement
      .newInstance(BatchType.LOGGED)
      .add(
        insertLobby.bind(
          lobby.id,
          lobby.inviteCode.value,
          lobby.hostNickname,
          lobby.hostSessionId,
          lobby.guestNickname.orNull,
          lobby.guestSessionId.orNull,
          lobby.visibility.toString,
          java.lang.Boolean.valueOf(lobby.allowUndo),
          java.lang.Boolean.valueOf(lobby.allowSpectate),
          java.lang.Integer.valueOf(lobby.spectatorLimit),
          lobby.status.toString,
          lobby.gameId.orNull,
          java.lang.Long.valueOf(lobby.createdAt),
          Instant.now
        )
      )
      .add(insertInverse.bind(lobby.inviteCode.value, lobby.id))
    ZIO.fromCompletionStage(session.executeAsync(batch)).unit
      .mapError(toInfraError)

  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    ZIO
      .fromCompletionStage(session.executeAsync(selectById.bind(id)))
      .mapError(toInfraError)
      .map(rs => Option(rs.one()).flatMap(rowToLobby))

  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    for
      rs <- ZIO
              .fromCompletionStage(
                session.executeAsync(selectInverse.bind(code.value))
              )
              .mapError(toInfraError)
      result <- Option(rs.one()).map(_.getString("lobby_id")) match
                  case None     => ZIO.succeed(None)
                  case Some(id) => findById(id)
    yield result

  def update(lobby: Lobby): IO[LobbyError, Unit] =
    // INSERT semantics in Cassandra are upsert; reuse the create batch.
    create(lobby)

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    for
      existing <- findById(id)
      _        <- existing match
                    case Some(l) =>
                      val batch = BatchStatement
                        .newInstance(BatchType.LOGGED)
                        .add(deleteLobby.bind(id))
                        .add(deleteInverse.bind(l.inviteCode.value))
                      ZIO
                        .fromCompletionStage(session.executeAsync(batch))
                        .unit
                        .mapError(toInfraError)
                    case None =>
                      ZIO
                        .fromCompletionStage(
                          session.executeAsync(deleteLobby.bind(id))
                        )
                        .unit
                        .mapError(toInfraError)
    yield ()

  def listPublicWaiting(): IO[LobbyError, List[Lobby]] =
    ZIO
      .fromCompletionStage(
        session.executeAsync(
          selectPublicWaiting.bind(
            LobbyVisibility.Public.toString,
            LobbyStatus.Waiting.toString
          )
        )
      )
      .mapError(toInfraError)
      .map { rs =>
        rs.currentPage().asScala.toList.flatMap(rowToLobby).sortBy(_.createdAt)
      }

  private def rowToLobby(row: Row): Option[Lobby] =
    for
      id          <- Option(row.getString("lobby_id"))
      rawCode     <- Option(row.getString("invite_code"))
      host        <- Option(row.getString("host_nickname"))
      hostSession <- Option(row.getString("host_session_id"))
      visibility  <- Option(row.getString("visibility"))
                       .flatMap(s =>
                         LobbyVisibility.values.find(_.toString == s)
                       )
      status      <- Option(row.getString("status"))
                       .flatMap(s => LobbyStatus.values.find(_.toString == s))
    yield Lobby(
      id = id,
      inviteCode = InviteCode.unsafe(rawCode),
      hostNickname = host,
      hostSessionId = hostSession,
      guestNickname = Option(row.getString("guest_nickname")),
      guestSessionId = Option(row.getString("guest_session_id")),
      visibility = visibility,
      allowUndo = row.getBoolean("allow_undo"),
      allowSpectate = row.getBoolean("allow_spectate"),
      spectatorLimit = row.getInt("spectator_limit"),
      status = status,
      createdAt = row.getLong("created_at"),
      gameId = Option(row.getString("game_id"))
    )

  private def toInfraError(t: Throwable): LobbyError =
    LobbyError.InfrastructureError(s"Cassandra error: ${t.getMessage}")

object CassandraLobbyRepository:
  val layer: URLayer[CqlSession, LobbyRepository] =
    ZLayer.fromFunction(CassandraLobbyRepository(_))

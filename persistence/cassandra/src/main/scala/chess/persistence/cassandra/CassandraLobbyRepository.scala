package chess.persistence.cassandra

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId, LobbyStatus}
import chess.persistence.LobbyRepository
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{BatchStatement, BatchType, Row}
import zio.*

import java.time.Instant

/** Cassandra-backed `LobbyRepository`. Two tables:
  *
  *   - `lobbies (lobby_id PK, ...)` — primary record
  *   - `lobbies_by_invite (invite_code PK, lobby_id)` — denormalised inverse
  *     for the join lookup
  *
  * Two-table writes are bundled into a logged batch so a partial-failure
  * doesn't leave the inverse table dangling.
  */
final class CassandraLobbyRepository(session: CqlSession) extends LobbyRepository:

  private val insertLobby = session.prepare("""
    INSERT INTO lobbies
      (lobby_id, invite_code, host_nickname, guest_nickname, status,
       game_id, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  """)
  private val insertInverse =
    session.prepare(
      "INSERT INTO lobbies_by_invite (invite_code, lobby_id) VALUES (?, ?)"
    )
  private val selectById =
    session.prepare("SELECT * FROM lobbies WHERE lobby_id = ?")
  private val selectInverse =
    session.prepare("SELECT lobby_id FROM lobbies_by_invite WHERE invite_code = ?")
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
          lobby.guestNickname.orNull,
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

  private def rowToLobby(row: Row): Option[Lobby] =
    for
      id      <- Option(row.getString("lobby_id"))
      rawCode <- Option(row.getString("invite_code"))
      host    <- Option(row.getString("host_nickname"))
      status  <- Option(row.getString("status"))
                   .flatMap(s =>
                     LobbyStatus.values.find(_.toString == s)
                   )
    yield Lobby(
      id = id,
      inviteCode = InviteCode.unsafe(rawCode),
      hostNickname = host,
      guestNickname = Option(row.getString("guest_nickname")),
      status = status,
      createdAt = row.getLong("created_at"),
      gameId = Option(row.getString("game_id"))
    )

  private def toInfraError(t: Throwable): LobbyError =
    LobbyError.InfrastructureError(s"Cassandra error: ${t.getMessage}")

object CassandraLobbyRepository:
  val layer: URLayer[CqlSession, LobbyRepository] =
    ZLayer.fromFunction(CassandraLobbyRepository(_))

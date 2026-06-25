package chess.persistence.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import zio.*
import zio.json.*

import chess.model.{GameArchive, GameError, GameId}
import chess.persistence.GameArchiveJson.given
import chess.persistence.GameArchiveRepository

/** Cassandra-backed `GameArchiveRepository`. One row per finished game in
  * `game_archives`, the archive stored as a JSON blob, via prepared statements.
  */
final class CassandraGameArchiveRepository(session: CqlSession)
    extends GameArchiveRepository:

  private val saveStmt =
    session.prepare("INSERT INTO game_archives (game_id, json) VALUES (?, ?)")
  private val findStmt =
    session.prepare("SELECT json FROM game_archives WHERE game_id = ?")

  def save(archive: GameArchive): IO[GameError, Unit] =
    val bound = saveStmt.bind(archive.gameId, archive.toJson)
    ZIO
      .fromCompletionStage(session.executeAsync(bound))
      .unit
      .mapError(toInfraError)

  def find(gameId: GameId): IO[GameError, Option[GameArchive]] =
    ZIO
      .fromCompletionStage(session.executeAsync(findStmt.bind(gameId)))
      .mapError(toInfraError)
      .flatMap { rs =>
        Option(rs.one()).map(_.getString("json")) match
          case None => ZIO.succeed(None)
          case Some(json) =>
            json.fromJson[GameArchive] match
              case Right(archive) => ZIO.succeed(Some(archive))
              case Left(err) =>
                ZIO.fail(
                  GameError.InfrastructureError(s"Archive decode failed: $err")
                )
      }

  private def toInfraError(t: Throwable): GameError =
    GameError.InfrastructureError(s"Cassandra error: ${t.getMessage}")

object CassandraGameArchiveRepository:
  val layer: URLayer[CqlSession, GameArchiveRepository] =
    ZLayer.fromFunction(CassandraGameArchiveRepository(_))

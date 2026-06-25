package chess.persistence.postgres

import slick.jdbc.PostgresProfile.api.*
import zio.*
import zio.json.*

import chess.model.{GameArchive, GameError, GameId}
import chess.persistence.GameArchiveJson.given
import chess.persistence.GameArchiveRepository

/** Slick-backed `GameArchiveRepository`. The finished game is stored as a JSON
  * blob in one TEXT column (`game_archives(id, json)`), upserted atomically.
  */
final class PostgresGameArchiveRepository(db: PostgresDatabase)
    extends GameArchiveRepository:

  def save(archive: GameArchive): IO[GameError, Unit] =
    val json = archive.toJson
    val upsert =
      sqlu"""
        INSERT INTO game_archives (id, json)
        VALUES (${archive.gameId}, ${json})
        ON CONFLICT (id) DO UPDATE SET json = EXCLUDED.json
      """
    db.run(upsert).unit.mapError(toInfraError)

  def find(gameId: GameId): IO[GameError, Option[GameArchive]] =
    val q = sql"SELECT json FROM game_archives WHERE id = $gameId".as[String].headOption
    db.run(q).mapError(toInfraError).flatMap {
      case None => ZIO.succeed(None)
      case Some(json) =>
        json.fromJson[GameArchive] match
          case Right(archive) => ZIO.succeed(Some(archive))
          case Left(err) =>
            ZIO.fail(GameError.InfrastructureError(s"Archive decode failed: $err"))
    }

  private def toInfraError(t: Throwable): GameError =
    GameError.InfrastructureError(s"Postgres error: ${t.getMessage}")

object PostgresGameArchiveRepository:
  val layer: URLayer[PostgresDatabase, GameArchiveRepository] =
    ZLayer.fromFunction(PostgresGameArchiveRepository(_))

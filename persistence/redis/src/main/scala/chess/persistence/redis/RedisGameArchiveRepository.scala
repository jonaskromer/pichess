package chess.persistence.redis

import zio.*
import zio.json.*
import zio.redis.Redis

import chess.model.{GameArchive, GameError, GameId}
import chess.persistence.GameArchiveJson.given
import chess.persistence.GameArchiveRepository

/** Redis-backed `GameArchiveRepository`. The finished game is stored as a JSON
  * blob under `archive:{id}` — no schema, durable by default.
  */
final class RedisGameArchiveRepository(redis: Redis)
    extends GameArchiveRepository:

  private def keyOf(id: GameId): String = s"archive:$id"

  def save(archive: GameArchive): IO[GameError, Unit] =
    redis
      .set(keyOf(archive.gameId), archive.toJson)
      .unit
      .mapError(toInfraError)

  def find(gameId: GameId): IO[GameError, Option[GameArchive]] =
    redis
      .get(keyOf(gameId))
      .returning[String]
      .mapError(toInfraError)
      .flatMap {
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
    GameError.InfrastructureError(s"Redis error: ${t.getMessage}")

object RedisGameArchiveRepository:
  val layer: URLayer[Redis, GameArchiveRepository] =
    ZLayer.fromFunction(RedisGameArchiveRepository(_))

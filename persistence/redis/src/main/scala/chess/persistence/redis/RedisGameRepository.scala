package chess.persistence.redis

import zio.*
import zio.redis.Redis

import chess.codec.{FenParserRegex, FenSerializer}
import chess.model.board.GameState
import chess.model.{GameError, GameId}
import chess.persistence.GameRepository

/** Redis-backed `GameRepository`. State is stored as the canonical FEN string
  * under `game:{id}` — no schema needed, no TTL by default (set durably).
  *
  * Uses the same FEN codec as the rest of the project, so a Redis-stored
  * value is interoperable with anything that can read FEN — handy for
  * cross-checking with other backends in tests.
  */
final class RedisGameRepository(redis: Redis) extends GameRepository:

  private def keyOf(id: GameId): String = s"game:$id"

  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    redis
      .set(keyOf(id), FenSerializer.serialize(state))
      .unit
      .mapError(toInfraError)

  def load(id: GameId): IO[GameError, Option[GameState]] =
    redis
      .get(keyOf(id))
      .returning[String]
      .mapError(toInfraError)
      .flatMap {
        case Some(fen) => FenParserRegex.parse(fen).map(Some(_))
        case None      => ZIO.succeed(None)
      }

  def delete(id: GameId): IO[GameError, Unit] =
    redis
      .del(keyOf(id))
      .unit
      .mapError(toInfraError)

  private def toInfraError(t: Throwable): GameError =
    GameError.InfrastructureError(s"Redis error: ${t.getMessage}")

object RedisGameRepository:
  val layer: URLayer[Redis, GameRepository] =
    ZLayer.fromFunction(RedisGameRepository(_))

package chess.persistence.cache

import chess.model.{GameError, GameId}
import chess.model.board.GameState
import chess.persistence.GameRepository
import zio.*

/** Read-through / write-through cache decorator. Reads consult `cache` first
  * and fall through to `primary` on miss; misses are then populated.
  * Writes go to `primary` first (durable store of record), then to `cache`,
  * so a crash between the two leaves the cache stale rather than promoting
  * unpersisted state. Deletes invalidate both.
  *
  * `cache` and `primary` must be different instances; using the same
  * implementation for both is allowed but pointless. Typically `cache` is a
  * `RedisGameRepository` and `primary` is a Postgres / Mongo / Cassandra
  * impl.
  */
final class CachedGameRepository(
    cache: GameRepository,
    primary: GameRepository
) extends GameRepository:

  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    primary.save(id, state) *> cache.save(id, state)

  def load(id: GameId): IO[GameError, Option[GameState]] =
    cache.load(id).flatMap {
      case some @ Some(_) => ZIO.succeed(some)
      case None =>
        primary.load(id).tap {
          case Some(state) => cache.save(id, state)
          case None        => ZIO.unit
        }
    }

  def delete(id: GameId): IO[GameError, Unit] =
    primary.delete(id) *> cache.delete(id)

object CachedGameRepository:

  /** Build a decorator from any cache + primary pair. The factory takes
    * tagged service references so consumers can swap either side via
    * ZLayer composition.
    */
  final case class Cache(repository: GameRepository)
  final case class Primary(repository: GameRepository)

  val layer: URLayer[Cache & Primary, GameRepository] =
    ZLayer.fromFunction { (cache: Cache, primary: Primary) =>
      new CachedGameRepository(cache.repository, primary.repository)
    }

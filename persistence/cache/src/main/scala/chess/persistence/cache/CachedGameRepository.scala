package chess.persistence.cache

import chess.model.{GameError, GameId}
import chess.model.board.GameState
import chess.persistence.GameRepository
import zio.*

/** Read-through cache decorator with parallel write-through.
  *
  * Reads consult `cache` first and fall through to `primary` on miss;
  * misses are then populated (sequential read path — no value in racing
  * a cold-cache read against the primary).
  *
  * Writes fan out to `primary` and `cache` in parallel via `zipPar`,
  * so the caller's latency is `max(primary, cache)` instead of the
  * sum. `primary` is the source of truth: its failure fails the
  * operation, its success completes it. `cache` failures are logged
  * and swallowed — a missing cache entry self-heals on the next read.
  *
  * Phantom-write window: if `cache` completes before `primary` and
  * `primary` then fails, the cache temporarily holds state that
  * doesn't exist in primary. The window is tiny (Redis is sub-ms,
  * primary failures are rare) and self-correcting on the next save
  * for the same id or on cache TTL expiry.
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
    primary
      .save(id, state)
      .zipPar(
        cache
          .save(id, state)
          .catchAllCause(c =>
            ZIO.logWarningCause(s"cache.save($id) failed — primary still authoritative", c)
          )
      )
      .unit

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
    primary
      .delete(id)
      .zipPar(
        cache
          .delete(id)
          .catchAllCause(c =>
            ZIO.logWarningCause(s"cache.delete($id) failed — primary still authoritative", c)
          )
      )
      .unit

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

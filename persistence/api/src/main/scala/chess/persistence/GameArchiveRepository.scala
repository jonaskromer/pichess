package chess.persistence

import zio.*

import chess.model.{GameArchive, GameError, GameId}

/** Persists finished games for post-game analysis / replay.
  *
  * Only the finalized [[GameArchive]] is stored — the async archive consumer
  * accumulates a game's plies in-memory (idempotent, order-agnostic) and calls
  * [[save]] once at game end. A restart rebuilds via Kafka replay (the same
  * earliest-offset replay every projection relies on), so the store needs no
  * per-move durability. [[find]] serves the archive to the analysis API / GUI.
  *
  * `save` is idempotent by `gameId` (last write wins), so at-least-once delivery
  * and replay converge to the same record.
  */
trait GameArchiveRepository:
  def save(archive: GameArchive): IO[GameError, Unit]
  def find(gameId: GameId): IO[GameError, Option[GameArchive]]

/** In-memory archive store (dev/test default). */
final class InMemoryGameArchiveRepository(store: Ref[Map[GameId, GameArchive]])
    extends GameArchiveRepository:

  def save(archive: GameArchive): IO[GameError, Unit] =
    store.update(_ + (archive.gameId -> archive))

  def find(gameId: GameId): IO[GameError, Option[GameArchive]] =
    store.get.map(_.get(gameId))

object InMemoryGameArchiveRepository:
  val layer: ULayer[GameArchiveRepository] =
    ZLayer {
      Ref.make(Map.empty[GameId, GameArchive]).map(InMemoryGameArchiveRepository(_))
    }

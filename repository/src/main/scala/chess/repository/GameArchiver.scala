package chess.repository

import zio.*

import chess.events.GameDomainEvent
import chess.events.GameDomainEvent.{DrawClaimed, Forfeited, GameEnded, MoveMade}
import chess.model.{ArchivePly, GameError, GameId}
import chess.opening.EcoBook
import chess.persistence.GameArchiveRepository

/** Folds `chess.game-events` into archived games.
  *
  *   - `MoveMade` → accumulate its ply in-memory, keyed `(gameId, ply)`, so
  *     redelivery / out-of-order arrival / topic replay converge to the same
  *     set; undo/redo events are ignored (a later move overwrites the ply, and
  *     finalize truncates to the final game length).
  *   - a terminal event (`GameEnded`/`Forfeited`/`DrawClaimed`) → finalize:
  *     atomically take-and-clear the game's plies, keep those up to the final
  *     position's ply count, build the [[chess.model.GameArchive]]
  *     (PGN-with-clocks + opening), and [[GameArchiveRepository.save]] it.
  *
  * Clearing on finalize bounds memory to in-flight games and makes a duplicate
  * terminal event a no-op (the second finds no plies). A restart rebuilds from
  * the earliest offset like every other projection. Order-agnostic + idempotent.
  */
final class GameArchiver(
    repo: GameArchiveRepository,
    eco: EcoBook,
    state: Ref[Map[GameId, Map[Int, ArchivePly]]]
):

  def handle(event: GameDomainEvent): IO[GameError, Unit] =
    event match
      case e: MoveMade =>
        ArchiveProjection.plyOf(e) match
          case Some(ply) =>
            state.update(games =>
              games + (e.gameId -> (games.getOrElse(e.gameId, Map.empty) + (ply.ply -> ply)))
            )
          case None =>
            ZIO.logWarning(s"Skipping unparseable MoveMade for ${e.gameId}")
      case e: GameEnded =>
        finalize(e.gameId, e.resultingFen, ArchiveProjection.resultToken(e), e.occurredAt)
      case e: Forfeited =>
        finalize(e.gameId, e.resultingFen, ArchiveProjection.resultToken(e), e.occurredAt)
      case e: DrawClaimed =>
        finalize(e.gameId, e.resultingFen, ArchiveProjection.resultToken(e), e.occurredAt)
      case _ =>
        ZIO.unit

  private def finalize(
      gameId: GameId,
      finalFen: String,
      result: String,
      finishedAt: Long
  ): IO[GameError, Unit] =
    state
      .modify(games => (games.getOrElse(gameId, Map.empty), games - gameId))
      .flatMap { plies =>
        ZIO.when(plies.nonEmpty) {
          val ordered = plies.values.toList.sortBy(_.ply)
          val kept = ArchiveProjection.plyCount(finalFen) match
            case Some(n) => ordered.filter(_.ply < n)
            case None    => ordered
          ArchiveBuilder
            .build(gameId, "local", "White", "Black", kept, result, finishedAt, eco, None)
            .flatMap(repo.save)
        }.unit
      }

object GameArchiver:
  def make(repo: GameArchiveRepository, eco: EcoBook): UIO[GameArchiver] =
    Ref
      .make(Map.empty[GameId, Map[Int, ArchivePly]])
      .map(GameArchiver(repo, eco, _))

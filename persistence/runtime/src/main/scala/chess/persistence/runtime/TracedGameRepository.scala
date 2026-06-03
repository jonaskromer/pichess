package chess.persistence.runtime

import chess.model.{GameError, GameId}
import chess.model.board.GameState
import chess.persistence.GameRepository
import io.opentelemetry.api.trace.SpanKind
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

/** Tracing decorator over any `GameRepository` backend. Each method is
  * wrapped in an internal span named `db.game-repo.<op>` so DB-call
  * latency shows up as a child of whatever request (HTTP, gRPC, Kafka
  * record) is being processed.
  *
  * The decorator is backend-agnostic — wired uniformly by
  * [[PersistenceLayers]] on top of every primary or cached backend, so
  * Postgres, Mongo, Cassandra, Redis, and the in-memory dev backend all
  * surface the same span shape.
  */
final class TracedGameRepository(underlying: GameRepository, tracing: Tracing)
    extends GameRepository:

  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    span("db.game-repo.save")(underlying.save(id, state))

  def load(id: GameId): IO[GameError, Option[GameState]] =
    span("db.game-repo.load")(underlying.load(id))

  def delete(id: GameId): IO[GameError, Unit] =
    span("db.game-repo.delete")(underlying.delete(id))

  private def span[A](name: String)(
      io: => IO[GameError, A]
  ): IO[GameError, A] =
    tracing.span(name, SpanKind.INTERNAL)(io)

object TracedGameRepository:

  /** Wrap an existing `GameRepository` layer with tracing. Output type
    * stays as `GameRepository` so the call site is a drop-in over the
    * untraced layer. Adds `Tracing` to the env requirement.
    */
  def wrap(
      underlying: ZLayer[Any, Throwable, GameRepository]
  ): ZLayer[Tracing, Throwable, GameRepository] =
    underlying ++ ZLayer.service[Tracing] >>>
      ZLayer.fromFunction { (repo: GameRepository, t: Tracing) =>
        new TracedGameRepository(repo, t): GameRepository
      }

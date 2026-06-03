package chess.persistence.runtime

import io.opentelemetry.api.trace.SpanKind
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId}
import chess.persistence.LobbyRepository

/** Tracing decorator over any `LobbyRepository` backend. Each method is
  * wrapped in an internal span named `db.lobby-repo.<op>` so DB-call
  * latency shows up as a child of whatever request is in flight.
  *
  * The decorator is backend-agnostic — wired uniformly by
  * [[PersistenceLayers]] on top of every primary or cached backend.
  */
final class TracedLobbyRepository(
    underlying: LobbyRepository,
    tracing: Tracing
) extends LobbyRepository:

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    span("db.lobby-repo.create")(underlying.create(lobby))

  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    span("db.lobby-repo.findById")(underlying.findById(id))

  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    span("db.lobby-repo.findByInviteCode")(underlying.findByInviteCode(code))

  def update(lobby: Lobby): IO[LobbyError, Unit] =
    span("db.lobby-repo.update")(underlying.update(lobby))

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    span("db.lobby-repo.delete")(underlying.delete(id))

  def listPublicWaiting(): IO[LobbyError, List[Lobby]] =
    span("db.lobby-repo.listPublicWaiting")(underlying.listPublicWaiting())

  private def span[A](name: String)(
      io: => IO[LobbyError, A]
  ): IO[LobbyError, A] =
    tracing.span(name, SpanKind.INTERNAL)(io)

object TracedLobbyRepository:

  def wrap(
      underlying: ZLayer[Any, Throwable, LobbyRepository]
  ): ZLayer[Tracing, Throwable, LobbyRepository] =
    underlying ++ ZLayer.service[Tracing] >>>
      ZLayer.fromFunction { (repo: LobbyRepository, t: Tracing) =>
        new TracedLobbyRepository(repo, t): LobbyRepository
      }

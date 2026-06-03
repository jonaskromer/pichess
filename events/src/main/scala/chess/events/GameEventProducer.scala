package chess.events

import zio.*

import chess.model.GameError

/** Publish-side abstraction for the `chess.game-events` topic.
  *
  * A failure surfaces as `GameError.InfrastructureError` so the caller can
  * decide whether to swallow it (best-effort projections) or fail the request
  * (durability-critical writes). gameService publishes after every successful
  * state transition.
  */
trait GameEventProducer:
  def publish(event: GameDomainEvent): IO[GameError, Unit]

object GameEventProducer:
  def publish(
      event: GameDomainEvent
  ): ZIO[GameEventProducer, GameError, Unit] =
    ZIO.serviceWithZIO[GameEventProducer](_.publish(event))

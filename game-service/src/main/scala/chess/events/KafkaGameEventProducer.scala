package chess.events

import chess.model.GameError
import zio.*
import zio.json.*
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde

/** zio-kafka backed producer. Records are keyed by `gameId` so per-game
  * ordering is preserved across event types (consumers see
  * `GameStarted → MoveMade → … → GameEnded` in order for any one game).
  *
  * `acks=all` and idempotence are on, so a successful `publish` means the
  * record is durably committed to Kafka. The MakeMove rpc returns to the
  * gateway only after this future resolves — keeps the invariant that no
  * client is told "your move succeeded" until the event is on the topic.
  */
final class KafkaGameEventProducer(producer: Producer) extends GameEventProducer:
  def publish(event: GameDomainEvent): IO[GameError, Unit] =
    producer
      .produce[Any, String, String](
        topic           = Topics.GameEvents,
        key             = event.gameId,
        value           = event.toJson,
        keySerializer   = Serde.string,
        valueSerializer = Serde.string
      )
      .unit
      .mapError(t =>
        GameError.InfrastructureError(s"Kafka publish failed: ${t.getMessage}")
      )

object KafkaGameEventProducer:
  def layer(
      bootstrapServers: String
  ): ZLayer[Any, Throwable, GameEventProducer] =
    ZLayer.scoped {
      val settings =
        ProducerSettings(bootstrapServers.split(',').toList.map(_.trim))
          .withProperty("acks", "all")
          .withProperty("enable.idempotence", "true")
      Producer.make(settings).map(new KafkaGameEventProducer(_))
    }

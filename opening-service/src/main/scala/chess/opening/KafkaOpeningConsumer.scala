package chess.opening

import chess.events.{GameDomainEvent, Topics}
import zio.*
import zio.json.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde

/** Kafka glue for the opening projection. Subscribes to
  * `chess.game-events`, deserialises each record into a [[GameDomainEvent]],
  * and hands it to [[OpeningProjection.applyEvent]].
  *
  * Failures inside `applyEvent` (e.g. a transient Neo4j error) are logged
  * but don't tear the stream down — one bad event must not block the rest
  * of the topic.
  */
object KafkaOpeningConsumer:

  def consumerLayer(
      bootstrapServers: String,
      consumerGroup: String
  ): ZLayer[Any, Throwable, Consumer] =
    val settings =
      ConsumerSettings(bootstrapServers.split(',').toList.map(_.trim))
        .withGroupId(consumerGroup)
        .withProperty("auto.offset.reset", "earliest")
    ZLayer.scoped(Consumer.make(settings))

  def run(projection: OpeningProjection): ZIO[Consumer, Throwable, Unit] =
    Consumer
      .plainStream(
        Subscription.topics(Topics.GameEvents),
        Serde.string,
        Serde.string
      )
      .tap { record =>
        record.value.fromJson[GameDomainEvent] match
          case Right(event) =>
            projection.applyEvent(event).catchAll(err =>
              ZIO.logError(
                s"Failed to project event for ${event.gameId}: ${err.getMessage}"
              )
            )
          case Left(err) =>
            ZIO.logWarning(
              s"Skipping malformed event from offset ${record.offset.offset}: $err"
            )
      }
      .map(_.offset)
      .aggregateAsync(Consumer.offsetBatches)
      .mapZIO(_.commit)
      .runDrain

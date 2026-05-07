package chess.analytics

import chess.events.{GameDomainEvent, Topics}
import zio.*
import zio.jdbc.ZConnectionPool
import zio.json.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde

/** Kafka glue for analytics. Subscribes to `chess.game-events` and forwards
  * each record to [[AnalyticsProjection.applyEvent]].
  */
object KafkaAnalyticsConsumer:

  def consumerLayer(
      bootstrapServers: String,
      consumerGroup: String
  ): ZLayer[Any, Throwable, Consumer] =
    val settings =
      ConsumerSettings(bootstrapServers.split(',').toList.map(_.trim))
        .withGroupId(consumerGroup)
        .withProperty("auto.offset.reset", "earliest")
    ZLayer.scoped(Consumer.make(settings))

  def run(
      projection: AnalyticsProjection
  ): ZIO[Consumer & ZConnectionPool, Throwable, Unit] =
    Consumer
      .plainStream(
        Subscription.topics(Topics.GameEvents),
        Serde.string,
        Serde.string
      )
      .tap { record =>
        record.value.fromJson[GameDomainEvent] match
          case Right(event) =>
            projection
              .applyEvent(event)
              .catchAll(err =>
                ZIO.logError(
                  s"Failed to insert event for ${event.gameId}: ${err.getMessage}"
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

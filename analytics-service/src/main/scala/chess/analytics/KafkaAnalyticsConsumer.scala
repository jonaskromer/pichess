package chess.analytics

import zio.*
import zio.json.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde

import chess.api.AnalyticsSummaryDto
import chess.events.Topics

/** Kafka glue for analytics. Subscribes to `chess.analytics` — the Spark
  * speed-layer output of per-completed-game [[AnalyticsSummaryDto]]s — and
  * folds each into the [[AnalyticsService]] in-memory state.
  *
  * `auto.offset.reset = earliest` so a restarted service rebuilds its full
  * aggregate by replaying the topic (the topic is the durable store now that
  * ClickHouse is gone — see ADR 022). These records are Spark-produced and
  * carry no `traceparent`, so no span extraction here.
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

  def run(svc: AnalyticsService): ZIO[Consumer, Throwable, Unit] =
    Consumer
      .plainStream(Subscription.topics(Topics.Analytics), Serde.string, Serde.string)
      .tap { record =>
        record.value.fromJson[AnalyticsSummaryDto] match
          case Right(summary) => svc.record(summary)
          case Left(err) =>
            ZIO.logWarning(
              s"Skipping malformed analytics summary at offset ${record.offset.offset}: $err"
            )
      }
      .map(_.offset)
      .aggregateAsync(Consumer.offsetBatches)
      .mapZIO(_.commit)
      .runDrain

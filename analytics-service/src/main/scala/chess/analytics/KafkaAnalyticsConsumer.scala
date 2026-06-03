package chess.analytics

import scala.jdk.CollectionConverters.*

import io.opentelemetry.api.trace.SpanKind
import org.apache.kafka.common.header.Headers
import zio.*
import zio.jdbc.ZConnectionPool
import zio.json.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import chess.events.{GameDomainEvent, Topics}

/** Kafka glue for analytics. Subscribes to `chess.game-events` and forwards
  * each record to [[AnalyticsProjection.applyEvent]].
  *
  * Each record's processing is wrapped in a CONSUMER span extracted from
  * the W3C `traceparent` Kafka header injected by
  * [[chess.events.KafkaGameEventProducer]] — so the analytics insert and
  * its downstream JDBC time show up as continuation of the originating
  * request's trace in Jaeger.
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
  ): ZIO[Consumer & ZConnectionPool & Tracing, Throwable, Unit] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      Consumer
        .plainStream(
          Subscription.topics(Topics.GameEvents),
          Serde.string,
          Serde.string
        )
        .tap { record =>
          record.value.fromJson[GameDomainEvent] match
            case Right(event) =>
              tracing.extractSpan(
                TraceContextPropagator.default,
                headersCarrier(record.record.headers()),
                s"kafka.process ${Topics.GameEvents}",
                SpanKind.CONSUMER
              ) {
                projection
                  .applyEvent(event)
                  .catchAll(err =>
                    ZIO.logError(
                      s"Failed to insert event for ${event.gameId}: ${err.getMessage}"
                    )
                  )
              }
            case Left(err) =>
              ZIO.logWarning(
                s"Skipping malformed event from offset ${record.offset.offset}: $err"
              )
        }
        .map(_.offset)
        .aggregateAsync(Consumer.offsetBatches)
        .mapZIO(_.commit)
        .runDrain
    }

  private def headersCarrier(
      hs: Headers
  ): IncomingContextCarrier[Headers] =
    new IncomingContextCarrier[Headers]:
      override val kernel: Headers = hs
      override def getAllKeys(carrier: Headers): Iterable[String] =
        carrier.asScala.iterator.map(_.key()).toSet
      override def getByKey(carrier: Headers, key: String): Option[String] =
        Option(carrier.lastHeader(key)).map(h => new String(h.value(), "UTF-8"))

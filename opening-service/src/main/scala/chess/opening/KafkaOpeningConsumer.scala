package chess.opening

import chess.events.{GameDomainEvent, Topics}
import io.opentelemetry.api.trace.SpanKind
import org.apache.kafka.common.header.Headers
import zio.*
import zio.json.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.jdk.CollectionConverters.*

/** Kafka glue for the opening projection. Subscribes to
  * `chess.game-events`, deserialises each record into a [[GameDomainEvent]],
  * and hands it to [[OpeningProjection.applyEvent]].
  *
  * Per-record processing is wrapped in a CONSUMER span whose parent
  * comes from the W3C `traceparent` header that
  * [[chess.events.KafkaGameEventProducer]] injects on the publish side.
  * The trace therefore continues from the originating gRPC hop through
  * the broker into the projection write — so Jaeger can show end-to-end
  * latency from "client move" to "opening tree updated."
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

  def run(
      projection: OpeningProjection
  ): ZIO[Consumer & Tracing, Throwable, Unit] =
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
                projection.applyEvent(event).catchAll(err =>
                  ZIO.logError(
                    s"Failed to project event for ${event.gameId}: ${err.getMessage}"
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

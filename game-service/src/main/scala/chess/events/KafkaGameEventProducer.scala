package chess.events

import scala.collection.mutable

import io.opentelemetry.api.trace.SpanKind
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.{RecordHeader, RecordHeaders}
import zio.*
import zio.json.*
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import chess.model.GameError

/** zio-kafka backed producer. Records are keyed by `gameId` so per-game
  * ordering is preserved across event types (consumers see `GameStarted →
  * MoveMade → … → GameEnded` in order for any one game).
  *
  * `acks=all` and idempotence are on, so a successful `publish` means the
  * record is durably committed to Kafka. The MakeMove rpc returns to the
  * gateway only after this future resolves — keeps the invariant that no client
  * is told "your move succeeded" until the event is on the topic.
  *
  * Each publish is wrapped in a PRODUCER span and the current trace context is
  * injected as W3C `traceparent` into the Kafka record headers. The downstream
  * consumers (`repository`, `opening-service`, `analytics-service`) extract
  * that header to start their own CONSUMER span, so a single trace can follow
  * an event from the originating HTTP request all the way through the Kafka
  * boundary into every projection.
  */
final class KafkaGameEventProducer(producer: Producer, tracing: Tracing)
    extends GameEventProducer:

  def publish(event: GameDomainEvent): IO[GameError, Unit] =
    tracing.span(s"kafka.send ${Topics.GameEvents}", SpanKind.PRODUCER) {
      for
        kafkaHeaders <- buildHeaders
        record = new ProducerRecord[String, String](
          Topics.GameEvents,
          null, // partition: let Kafka decide
          event.gameId,
          event.toJson,
          kafkaHeaders
        )
        _ <- producer
          .produce[Any, String, String](
            record,
            keySerializer = Serde.string,
            valueSerializer = Serde.string
          )
          .mapError(t =>
            GameError.InfrastructureError(
              s"Kafka publish failed: ${t.getMessage}"
            )
          )
      yield ()
    }

  /** Build a Kafka `RecordHeaders` carrying the W3C trace context for the
    * current span. The propagator writes `traceparent` (and optionally
    * `tracestate`) into a transient mutable map; we then copy each entry into a
    * `RecordHeader` with the ASCII bytes — the convention Kafka headers use for
    * textual values.
    */
  private def buildHeaders: UIO[RecordHeaders] =
    val carrier =
      OutgoingContextCarrier.default(mutable.Map.empty[String, String])
    tracing
      .injectSpan(TraceContextPropagator.default, carrier)
      .as {
        val headers = new RecordHeaders()
        carrier.kernel.foreach { case (k, v) =>
          headers.add(new RecordHeader(k, v.getBytes("UTF-8")))
        }
        headers
      }

object KafkaGameEventProducer:
  def layer(
      bootstrapServers: String
  ): ZLayer[Tracing, Throwable, GameEventProducer] =
    ZLayer.scoped {
      val settings =
        ProducerSettings(bootstrapServers.split(',').toList.map(_.trim))
          .withProperty("acks", "all")
          .withProperty("enable.idempotence", "true")
      for
        prod <- Producer.make(settings)
        tracing <- ZIO.service[Tracing]
      yield new KafkaGameEventProducer(prod, tracing)
    }

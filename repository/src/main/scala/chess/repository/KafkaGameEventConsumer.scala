package chess.repository

import chess.codec.FenParserRegex
import chess.events.{GameDomainEvent, Topics}
import chess.model.GameError
import chess.persistence.GameRepository
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

/** Subscribes to `chess.game-events` and applies each event to the local
  * repository (`repo.save(gameId, fen)`).
  *
  * Records are keyed by `gameId` so per-game ordering is preserved across
  * event types. Each event carries `resultingFen` — the canonical "what to
  * persist after this event" — so the consumer is type-agnostic: it just
  * parses the FEN and writes it under the game's id, regardless of whether
  * the event is `MoveMade`, `Undone`, `DrawClaimed`, etc.
  *
  * The W3C `traceparent` header injected by the producer
  * ([[chess.events.KafkaGameEventProducer]]) is extracted per-record and
  * used as the parent of a CONSUMER span around the `applyEvent` call —
  * so a single trace can span the originating gRPC hop, the Kafka send,
  * and every downstream projection.
  *
  * Malformed payloads and per-event apply failures are logged at warn/error
  * but do **not** kill the stream — one bad event must not block the rest of
  * the topic. Stream-level failures (broker disconnect, deserializer crash)
  * propagate up and tear the service down via the supervising scope.
  */
object KafkaGameEventConsumer:

  def consumerLayer(
      bootstrapServers: String,
      consumerGroup: String
  ): ZLayer[Any, Throwable, Consumer] =
    val settings =
      ConsumerSettings(bootstrapServers.split(',').toList.map(_.trim))
        .withGroupId(consumerGroup)
        .withProperty("auto.offset.reset", "earliest")
    ZLayer.scoped(Consumer.make(settings))

  /** Run forever, consuming events and writing to `repo`. Returns when the
    * surrounding scope is closed (e.g. service shutdown).
    */
  def run(repo: GameRepository): ZIO[Consumer & Tracing, Throwable, Unit] =
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
                applyEvent(repo, event).catchAll { err =>
                  ZIO.logError(
                    s"Failed to apply event for ${event.gameId}: ${err.message}"
                  )
                }
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

  /** Adapt a Kafka `Headers` map to the zio-telemetry incoming-carrier
    * interface. Trace headers are always UTF-8 ASCII (`traceparent` is
    * defined that way by the W3C spec) so the decode is safe.
    */
  private def headersCarrier(
      hs: Headers
  ): IncomingContextCarrier[Headers] =
    new IncomingContextCarrier[Headers]:
      override val kernel: Headers = hs
      override def getAllKeys(carrier: Headers): Iterable[String] =
        carrier.asScala.iterator.map(_.key()).toSet
      override def getByKey(carrier: Headers, key: String): Option[String] =
        Option(carrier.lastHeader(key)).map(h => new String(h.value(), "UTF-8"))

  private def applyEvent(
      repo: GameRepository,
      event: GameDomainEvent
  ): IO[GameError, Unit] =
    FenParserRegex
      .parse(event.resultingFen)
      .flatMap(state => repo.save(event.gameId, state))

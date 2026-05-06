package chess.repository

import chess.codec.FenParserRegex
import chess.events.{GameDomainEvent, Topics}
import chess.model.GameError
import zio.*
import zio.json.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde

/** Subscribes to `chess.game-events` and applies each event to the local
  * repository (`repo.save(gameId, fen)`).
  *
  * Records are keyed by `gameId` so per-game ordering is preserved across
  * event types. Each event carries `resultingFen` — the canonical "what to
  * persist after this event" — so the consumer is type-agnostic: it just
  * parses the FEN and writes it under the game's id, regardless of whether
  * the event is `MoveMade`, `Undone`, `DrawClaimed`, etc.
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
  def run(repo: GameRepository): ZIO[Consumer, Throwable, Unit] =
    Consumer
      .plainStream(
        Subscription.topics(Topics.GameEvents),
        Serde.string,
        Serde.string
      )
      .tap { record =>
        record.value.fromJson[GameDomainEvent] match
          case Right(event) =>
            applyEvent(repo, event).catchAll { err =>
              ZIO.logError(
                s"Failed to apply event for ${event.gameId}: ${err.message}"
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

  private def applyEvent(
      repo: GameRepository,
      event: GameDomainEvent
  ): IO[GameError, Unit] =
    FenParserRegex
      .parse(event.resultingFen)
      .flatMap(state => repo.save(event.gameId, state))

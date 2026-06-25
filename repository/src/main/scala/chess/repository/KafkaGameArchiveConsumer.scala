package chess.repository

import zio.*
import zio.json.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde

import chess.events.{GameDomainEvent, Topics}
import chess.opening.EcoBook
import chess.persistence.GameArchiveRepository

/** Async, idempotent archive consumer: folds `chess.game-events` into the
  * [[GameArchiveRepository]] via [[GameArchiver]]. Runs alongside the repository's
  * read-side consumer as its own group + fiber, non-blocking on game-service.
  * `auto.offset.reset = earliest` + the idempotent upsert mean a restart safely
  * rebuilds archives by replaying the topic.
  */
object KafkaGameArchiveConsumer:

  def consumerLayer(
      bootstrapServers: String,
      consumerGroup: String
  ): ZLayer[Any, Throwable, Consumer] =
    val settings =
      ConsumerSettings(bootstrapServers.split(',').toList.map(_.trim))
        .withGroupId(consumerGroup)
        .withProperty("auto.offset.reset", "earliest")
    ZLayer.scoped(Consumer.make(settings))

  def run(repo: GameArchiveRepository): ZIO[Consumer, Throwable, Unit] =
    for
      eco      <- EcoBook.load
      archiver <- GameArchiver.make(repo, eco)
      _        <- consume(archiver)
    yield ()

  private def consume(archiver: GameArchiver): ZIO[Consumer, Throwable, Unit] =
    Consumer
      .plainStream(Subscription.topics(Topics.GameEvents), Serde.string, Serde.string)
      .tap { record =>
        record.value.fromJson[GameDomainEvent] match
          case Right(event) =>
            archiver
              .handle(event)
              .catchAll(err =>
                ZIO.logError(
                  s"Failed to archive event for ${event.gameId}: ${err.message}"
                )
              )
          case Left(err) =>
            ZIO.logWarning(
              s"Skipping malformed event at offset ${record.offset.offset}: $err"
            )
      }
      .map(_.offset)
      .aggregateAsync(Consumer.offsetBatches)
      .mapZIO(_.commit)
      .runDrain

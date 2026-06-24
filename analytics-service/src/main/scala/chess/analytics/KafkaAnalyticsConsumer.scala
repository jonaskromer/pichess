package chess.analytics

import zio.*
import zio.json.*
import zio.kafka.consumer.{Consumer, ConsumerSettings, Subscription}
import zio.kafka.serde.Serde

import chess.api.AnalyticsSummaryDto
import chess.events.{GameDomainEvent, Topics}

/** Kafka glue for analytics. Two consumers:
  *
  *   - [[run]] on `chess.analytics` (Spark-sessionized completed-game summaries)
  *     → folds the [[AnalyticsService]] aggregates, completed-game [[AnalyticsMetrics]],
  *     and the [[Records]] leaderboard.
  *   - [[runRaw]] on `chess.game-events` (raw event stream) → per-event rate and
  *     classifier metrics (moves, captures, checks, castles, promotions,
  *     takebacks, endings) and the live active-games gauge.
  *
  * `auto.offset.reset = earliest` so a restart rebuilds state by replaying;
  * dedup downstream keeps the aggregates correct under at-least-once.
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
    Ref.make(Records.empty).flatMap { records =>
      Consumer
        .plainStream(Subscription.topics(Topics.Analytics), Serde.string, Serde.string)
        .tap { record =>
          record.value.fromJson[AnalyticsSummaryDto] match
            case Right(summary) =>
              svc.record(summary) *>
                AnalyticsMetrics.gameCompleted(summary) *>
                records.updateAndGet(Records.fold(_, summary)).flatMap(AnalyticsMetrics.setRecords)
            case Left(err) =>
              ZIO.logWarning(
                s"Skipping malformed analytics summary at offset ${record.offset.offset}: $err"
              )
        }
        .map(_.offset)
        .aggregateAsync(Consumer.offsetBatches)
        .mapZIO(_.commit)
        .runDrain
    }

  def runRaw: ZIO[Consumer, Throwable, Unit] =
    Ref.make(Set.empty[String]).flatMap { active =>
      Consumer
        .plainStream(Subscription.topics(Topics.GameEvents), Serde.string, Serde.string)
        .tap { record =>
          record.value.fromJson[GameDomainEvent] match
            case Right(event) => handle(event, active)
            case Left(err) =>
              ZIO.logWarning(
                s"Skipping malformed game event at offset ${record.offset.offset}: $err"
              )
        }
        .map(_.offset)
        .aggregateAsync(Consumer.offsetBatches)
        .mapZIO(_.commit)
        .runDrain
    }

  private def handle(event: GameDomainEvent, active: Ref[Set[String]]): UIO[Unit] =
    import GameDomainEvent.*
    def started(id: String) =
      active.updateAndGet(_ + id).flatMap(s => AnalyticsMetrics.setActive(s.size))
    def ended(id: String) =
      active.updateAndGet(_ - id).flatMap(s => AnalyticsMetrics.setActive(s.size))
    event match
      case e: GameStarted => AnalyticsMetrics.gameStarted *> started(e.gameId)
      case e: GameLoaded  => started(e.gameId)
      case e: MoveMade    => AnalyticsMetrics.moveMade(e.san)
      case _: Undone      => AnalyticsMetrics.takeback("undo")
      case _: Redone      => AnalyticsMetrics.takeback("redo")
      case e: Forfeited   => AnalyticsMetrics.gameEnded("Forfeited") *> ended(e.gameId)
      case e: DrawClaimed => AnalyticsMetrics.drawClaimed(e.reason) *> ended(e.gameId)
      case e: GameEnded   => AnalyticsMetrics.gameEnded("GameEnded") *> ended(e.gameId)

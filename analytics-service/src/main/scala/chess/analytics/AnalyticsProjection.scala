package chess.analytics

import java.sql.Timestamp

import zio.*
import zio.jdbc.*

import chess.events.GameDomainEvent

/** Pure projection logic: translates one domain event into one ClickHouse
  * `move_events` row. Kafka glue lives in [[KafkaAnalyticsConsumer]] so
  * this stays unit-testable.
  *
  * Only `MoveMade` carries a meaningful SAN; for other event types the SAN
  * column is left as the empty string. The event_type discriminator lets
  * downstream queries filter to just `'MoveMade'` for "move played" stats.
  */
trait AnalyticsProjection:
  def applyEvent(event: GameDomainEvent): ZIO[ZConnectionPool, Throwable, Unit]

object AnalyticsProjection:
  val layer: ULayer[AnalyticsProjection] = ZLayer.succeed(LiveAnalyticsProjection)

private object LiveAnalyticsProjection extends AnalyticsProjection:

  def applyEvent(
      event: GameDomainEvent
  ): ZIO[ZConnectionPool, Throwable, Unit] =
    val (kind, san) = AnalyticsEventMapping.eventTypeAndSan(event)
    val occurredAt = Timestamp(event.occurredAt)

    // ClickHouse JDBC doesn't implement the prepareStatement overload
    // that zio-jdbc's `.insert` uses; see ClickHouseJdbc for the
    // background and the no-arg-prepareStatement workaround.
    ClickHouseJdbc.execute(
      """
        INSERT INTO move_events (game_id, event_type, san, fen, occurred_at)
        VALUES (?, ?, ?, ?, ?)
      """,
      event.gameId,
      kind,
      san,
      event.resultingFen,
      occurredAt
    )

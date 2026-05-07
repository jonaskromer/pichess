package chess.analytics

import zio.*
import zio.jdbc.*

/** Read-side service for the analytics REST surface. Three canonical
  * aggregate queries that the future admin panel will surface as charts.
  *
  * Queries are hand-written SQL — kept simple so the structure is obvious;
  * heavier reporting can grow into materialised views without touching
  * this trait.
  */
trait AnalyticsService:
  def topMoves(limit: Int): Task[List[(String, Long)]]
  def averageGameLength: Task[Option[Double]]
  def gameCount: Task[Long]

object AnalyticsService:
  def topMoves(limit: Int): ZIO[AnalyticsService, Throwable, List[(String, Long)]] =
    ZIO.serviceWithZIO[AnalyticsService](_.topMoves(limit))

  val averageGameLength: ZIO[AnalyticsService, Throwable, Option[Double]] =
    ZIO.serviceWithZIO[AnalyticsService](_.averageGameLength)

  val gameCount: ZIO[AnalyticsService, Throwable, Long] =
    ZIO.serviceWithZIO[AnalyticsService](_.gameCount)

  val layer: URLayer[ZConnectionPool, AnalyticsService] =
    ZLayer.fromFunction(LiveAnalyticsService(_))

private final class LiveAnalyticsService(pool: ZConnectionPool)
    extends AnalyticsService:

  // All SQL goes through ClickHouseJdbc rather than zio-jdbc's `.query`
  // because the underlying driver doesn't implement the prepareStatement
  // overload zio-jdbc uses. See ClickHouseJdbc for details.

  def topMoves(limit: Int): Task[List[(String, Long)]] =
    ClickHouseJdbc
      .query(
        """
        SELECT san, count(*) AS plays
        FROM move_events
        WHERE event_type = 'MoveMade' AND san <> ''
        GROUP BY san
        ORDER BY plays DESC
        LIMIT ?
      """,
        limit
      )(rs => (rs.getString(1), rs.getLong(2)))
      .provideEnvironment(ZEnvironment(pool))

  def averageGameLength: Task[Option[Double]] =
    ClickHouseJdbc
      .queryOne(
        """
        SELECT avg(c)
        FROM (
          SELECT count(*) AS c
          FROM move_events
          WHERE event_type = 'MoveMade'
          GROUP BY game_id
        )
      """
      )(rs =>
        // avg(...) returns NULL when the table is empty, so we coalesce
        // through wasNull() into None.
        val v = rs.getDouble(1)
        if rs.wasNull() then None else Some(v)
      )
      .map(_.flatten)
      .provideEnvironment(ZEnvironment(pool))

  def gameCount: Task[Long] =
    ClickHouseJdbc
      .queryOne(
        "SELECT countDistinct(game_id) FROM move_events"
      )(_.getLong(1))
      .map(_.getOrElse(0L))
      .provideEnvironment(ZEnvironment(pool))

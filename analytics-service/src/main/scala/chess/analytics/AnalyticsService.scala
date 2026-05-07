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

  def topMoves(limit: Int): Task[List[(String, Long)]] =
    transaction {
      sql"""
        SELECT san, count(*) AS plays
        FROM move_events
        WHERE event_type = 'MoveMade' AND san <> ''
        GROUP BY san
        ORDER BY plays DESC
        LIMIT $limit
      """.query[(String, Long)].selectAll.map(_.toList)
    }.provideEnvironment(ZEnvironment(pool))

  def averageGameLength: Task[Option[Double]] =
    transaction {
      sql"""
        SELECT avg(c)
        FROM (
          SELECT count(*) AS c
          FROM move_events
          WHERE event_type = 'MoveMade'
          GROUP BY game_id
        )
      """.query[Option[Double]].selectOne.map(_.flatten)
    }.provideEnvironment(ZEnvironment(pool))

  def gameCount: Task[Long] =
    transaction {
      sql"""
        SELECT countDistinct(game_id) FROM move_events
      """.query[Long].selectOne.map(_.getOrElse(0L))
    }.provideEnvironment(ZEnvironment(pool))

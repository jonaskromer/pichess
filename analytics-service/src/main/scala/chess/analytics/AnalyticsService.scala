package chess.analytics

import zio.*

/** Read-side service for the analytics REST surface. Three canonical
  * aggregate queries that the future admin panel will surface as charts.
  *
  * Queries are hand-written SQL — kept simple so the structure is obvious;
  * heavier reporting can grow into materialised views without touching
  * this trait.
  *
  * Only the trait + ZIO service accessors live here so this file stays in
  * statement coverage. The default JDBC-backed impl + its ZLayer are in
  * [[LiveAnalyticsService]], which needs a live ClickHouse to drive and
  * therefore stays excluded.
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

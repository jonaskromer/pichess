package chess.analytics

import zio.*

import chess.api.AnalyticsSummaryDto

/** Read-side service for the analytics REST surface, now fed by the Spark
  * speed layer (`chess.analytics` stream) rather than ClickHouse — see ADR 022.
  * `record` folds in one completed-game summary; the three queries serve the
  * canonical aggregates from the in-memory [[AnalyticsState]].
  *
  * `topMoves` returns the top **openings** (the move-signature each
  * `GameSummary` carries), preserving the `/analytics/openings/top` contract.
  */
trait AnalyticsService:
  def record(summary: AnalyticsSummaryDto): UIO[Unit]
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

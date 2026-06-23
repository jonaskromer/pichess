package chess.analytics

import zio.*

import chess.api.AnalyticsSummaryDto

/** In-memory [[AnalyticsService]] over a `Ref[AnalyticsState]`. The Spark speed
  * layer does the heavy aggregation; this service just folds the resulting
  * per-game summaries and serves them — no database. Restart recovery comes
  * from replaying `chess.analytics` from the start (the consumer resets to
  * earliest), so the durable store is the Kafka topic itself.
  */
object LiveAnalyticsService:
  val layer: ULayer[AnalyticsService] =
    ZLayer.fromZIO(Ref.make(AnalyticsState.empty).map(new LiveAnalyticsService(_)))

private[analytics] final class LiveAnalyticsService(ref: Ref[AnalyticsState])
    extends AnalyticsService:

  def record(summary: AnalyticsSummaryDto): UIO[Unit] =
    ref.update(AnalyticsState.fold(_, summary))

  def topMoves(limit: Int): Task[List[(String, Long)]] =
    ref.get.map(_.topOpenings(limit))

  def averageGameLength: Task[Option[Double]] =
    ref.get.map(_.averagePlies)

  def gameCount: Task[Long] =
    ref.get.map(_.games)

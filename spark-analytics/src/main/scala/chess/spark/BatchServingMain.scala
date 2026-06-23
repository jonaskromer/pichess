package chess.spark

import scala3encoders.given

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.agg.{Aggregations, Serving}
import chess.spark.schema.{EventDecoding, MoveEventRow}

/** Phase 3 — "Lambda for real": the batch layer recomputes the authoritative
  * event-count view over the full archive and **persists it to Parquet** (the
  * durable serving store), then the serving layer reads that back and
  * **reconciles it with a speed-layer increment** ([[Serving.mergeEventCounts]]).
  *
  * To make the reconciliation observable on a small fixture, the archive is
  * split at a timestamp into an "already-batched" slice (written to Parquet)
  * and a "recent" slice (the speed delta). The merged result is shown to equal
  * a from-scratch aggregate over the whole archive — i.e. the layers reconcile.
  */
object BatchServingMain extends ZIOAppDefault:

  private val eventsPath  = "spark-analytics/data/events/*.json"
  private val servingPath = "spark-analytics/data/serving/event-counts"
  // Split point for the demo: events before this are "batched", the rest are
  // the live speed delta. Chosen to fall between the fixture's two games.
  private val splitTs = 1718000005000L

  private def readRows(path: String): SIO[Dataset[MoveEventRow]] =
    SparkSession.read
      .textFile(path)
      .map(_.flatMap(line => EventDecoding.parseRow(line).toOption))

  private def job: ZIO[SparkSession, Throwable, Unit] =
    for
      rows       <- readRows(eventsPath)
      // Batch layer: authoritative view over the already-batched slice → Parquet.
      batchView   = Aggregations.eventTypeBreakdown(rows.filter(_.occurredAt < splitTs))
      _          <- batchView.write.saveUsing(_.mode("overwrite").parquet(servingPath))
      _          <- Console.printLine(s"[spark-analytics] wrote batch serving view → $servingPath")
      // Serving layer: read the persisted batch view back (no recompute)…
      servedBatch <- SparkSession.read.parquet(servingPath)
      // …and reconcile with the speed-layer increment (recent slice).
      speedView   = Aggregations.eventTypeBreakdown(rows.filter(_.occurredAt >= splitTs))
      merged      = Serving.mergeEventCounts(servedBatch, speedView)
      _          <- Console.printLine("\n[spark-analytics] batch view (from Parquet):")
      _          <- servedBatch.show(truncate = false)
      _          <- Console.printLine("[spark-analytics] speed delta:")
      _          <- speedView.show(truncate = false)
      _          <- Console.printLine("[spark-analytics] merged serving view (batch ⊕ speed):")
      _          <- merged.show(truncate = false)
      _          <- Console.printLine("[spark-analytics] cross-check — full-archive aggregate:")
      _          <- Aggregations.eventTypeBreakdown(rows).show(truncate = false)
    yield ()

  private val session =
    SparkSession.builder
      .master(localAllNodes)
      .appName("pichess-spark-batch-serving")
      .asLayer

  override def run: ZIO[ZIOAppArgs, Any, Any] = job.provide(session)

package chess.spark.agg

import scala3encoders.given

import org.apache.spark.sql.functions.{col, desc, window}

import zio.*
import zio.spark.sql.*
import zio.spark.sql.TryAnalysis.syntax.throwAnalysisException

import chess.spark.schema.MoveEventRow

/** LAYER 3 — the aggregation surface, kept pure (`Dataset`/`DataFrame` in,
  * lazy transform or effect out) and deliberately mode-agnostic. The
  * `*Breakdown` transformations are streaming-safe (a bare `groupBy().count`
  * with no ordering, which Structured Streaming accepts in `Complete` output
  * mode), so the batch job and the streaming job run the **same** code. The
  * action-returning helpers and anything with `orderBy` are batch-only —
  * streaming Datasets reject both `count()` actions and unbounded sorts.
  *
  * `scala3encoders.given` is imported directly (zio-spark 0.12.0 keeps the
  * Scala 3 `Encoder`s in the `scala3encoders` package, not re-exported by
  * `zio.spark.sql.implicits`); `throwAnalysisException` lets the `TryAnalysis`
  * results of `orderBy`/`limit` flow through as plain values.
  */
object Aggregations:

  // ---- batch actions (Task[_]) — not valid on streaming Datasets ------------

  /** Total events ingested. */
  def eventCount(rows: Dataset[MoveEventRow]): Task[Long] =
    rows.count

  /** How many of those events were actual moves (rows that carry a SAN). */
  def moveCount(rows: Dataset[MoveEventRow]): Task[Long] =
    rows.filter(_.eventType == "MoveMade").count

  /** Distinct games observed. */
  def gameCount(rows: Dataset[MoveEventRow]): Task[Long] =
    rows.map(_.gameId).distinct.count

  /** Mean number of moves per game over the batch. */
  def averageGameLength(rows: Dataset[MoveEventRow]): Task[Double] =
    for
      moves <- moveCount(rows)
      games <- gameCount(rows)
    yield if games == 0 then 0.0 else moves.toDouble / games.toDouble

  // ---- lazy transformations (DataFrame) -------------------------------------

  /** Count of events per type — streaming-safe (no ordering). Used by both the
    * batch summary and the streaming console sink (`Complete` mode).
    */
  def eventTypeBreakdown(rows: Dataset[MoveEventRow]): DataFrame =
    rows.groupBy(col("eventType")).count

  /** Event-time windowed counts of events per type, using `occurredAt` (epoch
    * ms) as the event time. The watermark bounds how long state is kept for
    * late records — the canonical "I understand streaming time semantics"
    * pattern. Streaming-friendly in `Update` output mode.
    */
  def windowedEventCounts(
      rows: Dataset[MoveEventRow],
      windowDuration: String,
      watermark: String
  ): DataFrame =
    rows
      .withColumn("eventTime", (col("occurredAt") / 1000).cast("timestamp"))
      .withWatermark("eventTime", watermark)
      .groupBy(window(col("eventTime"), windowDuration), col("eventType"))
      .count

  /** The `top` most-played moves by SAN. Batch-only: the `orderBy` makes this
    * an unbounded sort that Structured Streaming would reject.
    */
  def openingPopularity(rows: Dataset[MoveEventRow], top: Int): DataFrame =
    rows
      .filter(_.eventType == "MoveMade")
      .groupBy(col("san"))
      .count
      .orderBy(desc("count"))
      .limit(top)

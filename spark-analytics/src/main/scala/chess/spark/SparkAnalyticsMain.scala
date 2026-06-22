package chess.spark

import scala3encoders.given

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.agg.Aggregations
import chess.spark.schema.{EventDecoding, MoveEventRow}

/** Batch entrypoint — the "first read from a file" half of the Spark task.
  *
  * Reads a newline-delimited JSON dump of `chess.game-events` as raw text,
  * decodes each line through the shared zio-json codec (see [[EventDecoding]]),
  * drops anything malformed, flattens to `Dataset[MoveEventRow]`, and prints a
  * few aggregates. The streaming sibling (`StreamJob`, reading the live Kafka
  * topic) reuses the identical decode + [[Aggregations]] pipeline.
  *
  * `scala3encoders.given` is imported directly: in zio-spark 0.12.0 the Scala 3
  * `Encoder` instances live in the `scala3encoders` package and the library's
  * own `zio.spark.sql.implicits` only imports them into its own scope, so a
  * wildcard import of `implicits` does not re-export them.
  */
object SparkAnalyticsMain extends ZIOAppDefault:

  /** Archived event dump consumed by the batch job. */
  private val eventsPath = "spark-analytics/data/events/*.json"

  /** text → decode (shared codec) → flatten to rows, discarding bad lines. */
  private def readRows(path: String): SIO[Dataset[MoveEventRow]] =
    SparkSession.read
      .textFile(path)
      .map(_.flatMap(line => EventDecoding.parseRow(line).toOption))

  private val job: ZIO[SparkSession, Throwable, Unit] =
    for
      rows   <- readRows(eventsPath)
      events <- Aggregations.eventCount(rows)
      moves  <- Aggregations.moveCount(rows)
      games  <- Aggregations.gameCount(rows)
      avgLen <- Aggregations.averageGameLength(rows)
      _      <- Console.printLine(
                  f"[spark-analytics] events=$events moves=$moves games=$games avgLen=$avgLen%.1f"
                )
      _      <- Console.printLine("[spark-analytics] top openings:")
      _      <- Aggregations.openingPopularity(rows, top = 10).show(numRows = 10, truncate = false)
    yield ()

  private val session =
    SparkSession.builder
      .master(localAllNodes)
      .appName("pichess-spark-analytics")
      .asLayer

  override def run: ZIO[ZIOAppArgs, Any, Any] = job.provide(session)

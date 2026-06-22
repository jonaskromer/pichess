package chess.spark

import scala3encoders.given

import org.apache.spark.sql.streaming.OutputMode

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.agg.Aggregations
import chess.spark.schema.{EventDecoding, MoveEventRow, RawGameEvent}

import java.nio.charset.StandardCharsets.UTF_8

/** Streaming entrypoint — the "connect Spark Streaming to Kafka" half of the
  * Spark task, and the speed layer of the Lambda architecture.
  *
  * Subscribes to the live `chess.game-events` topic via Structured Streaming,
  * decodes each Kafka value through the **same** shared zio-json codec the batch
  * job uses (see [[EventDecoding]]), flattens to `Dataset[MoveEventRow]`, and
  * drives the identical [[Aggregations]] surface — here a running per-event-type
  * breakdown emitted to a console sink in `Complete` output mode.
  *
  * zio-spark 0.12.0's typed `DataStreamReader` exposes only file/socket sources
  * (no generic `format`/`load`), so the Kafka source is built through the
  * `fromSpark` escape hatch onto the underlying `SparkSession.readStream`, then
  * re-wrapped with `.zioSpark`.
  */
object StreamAnalyticsMain extends ZIOAppDefault:

  /** Underlying-API Kafka source, re-wrapped as a zio-spark `DataFrame`. */
  private def kafkaSource(bootstrap: String, topic: String): SIO[DataFrame] =
    fromSpark { ss =>
      ss.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", bootstrap)
        .option("subscribe", topic)
        .option("startingOffsets", "earliest")
        .load()
        .zioSpark
    }

  /** Kafka `value` (binary) → shared-codec decode → flat rows, dropping nulls
    * and malformed records. `flatMap` is stateless, so it is streaming-safe.
    */
  private def decodeRows(df: DataFrame): Dataset[MoveEventRow] =
    df.flatMap { row =>
      Option(row.getAs[Array[Byte]]("value"))
        .flatMap(bytes => EventDecoding.parseRow(new String(bytes, UTF_8)).toOption)
    }

  private def job(bootstrap: String): ZIO[SparkSession, Throwable, Unit] =
    for
      source <- kafkaSource(bootstrap, RawGameEvent.Topic)
      rows    = decodeRows(source)
      summary = Aggregations.eventTypeBreakdown(rows)
      _      <- Console.printLine(
                  s"[spark-analytics] streaming '${RawGameEvent.Topic}' from $bootstrap"
                )
      query  <- summary.writeStream
                  .format("console")
                  .option("truncate", value = false)
                  .outputMode(OutputMode.Complete())
                  .start
      _      <- ZIO.attemptBlocking(query.awaitTermination())
    yield ()

  private val session =
    SparkSession.builder
      .master(localAllNodes)
      .appName("pichess-spark-analytics-stream")
      .asLayer

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      bootstrap <- zio.System
                     .env("KAFKA_BOOTSTRAP_SERVERS")
                     .map(_.filter(_.trim.nonEmpty).getOrElse("localhost:9092"))
      _         <- job(bootstrap).provide(session)
    yield ()

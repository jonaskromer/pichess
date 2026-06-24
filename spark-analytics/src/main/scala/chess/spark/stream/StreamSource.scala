package chess.spark.stream

import scala3encoders.given

import zio.*
import zio.spark.sql.*

import chess.spark.schema.{EventDecoding, MoveEventRow}

import java.nio.charset.StandardCharsets.UTF_8

/** Shared Kafka ingress for every streaming job. zio-spark 0.12.0's typed
  * `DataStreamReader` exposes only file/socket sources, so the Kafka source is
  * built through the `fromSpark` escape hatch onto the underlying
  * `SparkSession.readStream` and re-wrapped with `.zioSpark`.
  */
object StreamSource:

  /** Underlying-API Kafka source, re-wrapped as a zio-spark `DataFrame`. */
  def kafka(bootstrap: String, topic: String): SIO[DataFrame] =
    fromSpark { ss =>
      ss.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", bootstrap)
        .option("subscribe", topic)
        .option("startingOffsets", "earliest")
        // Speed-layer source: tolerate the checkpoint referencing offsets a
        // (possibly recreated/ephemeral) broker no longer has, instead of
        // crashing. Any resulting reprocessing is made harmless by gameId
        // dedup downstream.
        .option("failOnDataLoss", "false")
        .load()
        .zioSpark
    }

  /** Kafka `value` (binary) → shared-codec decode → flat rows, dropping nulls
    * and malformed records. `flatMap` is stateless, so it is streaming-safe.
    */
  def decodeRows(df: DataFrame): Dataset[MoveEventRow] =
    df.flatMap { row =>
      Option(row.getAs[Array[Byte]]("value"))
        .flatMap(bytes => EventDecoding.parseRow(new String(bytes, UTF_8)).toOption)
    }

  /** `KAFKA_BOOTSTRAP_SERVERS` or a localhost default. */
  val bootstrapFromEnv: UIO[String] =
    zio.System
      .env("KAFKA_BOOTSTRAP_SERVERS")
      .map(_.filter(_.trim.nonEmpty).getOrElse("localhost:9092"))
      .orDie

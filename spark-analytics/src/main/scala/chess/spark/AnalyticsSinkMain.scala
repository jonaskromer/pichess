package chess.spark

import scala3encoders.given
import zio.json.*

import org.apache.spark.sql.streaming.OutputMode

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.schema.RawGameEvent
import chess.spark.session.{GameSummary, SessionPipeline}
import chess.spark.stream.StreamSource

/** The speed-layer loop-back: Spark reads `chess.game-events`, sessionizes it,
  * and **publishes one JSON [[GameSummary]] per completed game to the
  * `chess.analytics` Kafka topic** — which the gateway relays to the web-ui
  * over SSE for a live panel. This closes the reactive loop: events in → Spark
  * compute → analytics back onto the bus → live UI, no OLAP database in the
  * middle.
  *
  * A `Dataset[String]` carries its single column as `value`, exactly what the
  * Kafka sink expects; the sink requires a checkpoint location for offset/state
  * recovery (`PICHESS_SPARK_CHECKPOINT`).
  */
object AnalyticsSinkMain extends ZIOAppDefault:

  private val checkpoint =
    sys.env.getOrElse("PICHESS_SPARK_CHECKPOINT", "/tmp/pichess-spark/analytics-sink")

  private def job(bootstrap: String): ZIO[SparkSession, Throwable, Unit] =
    for
      source <- StreamSource.kafka(bootstrap, RawGameEvent.Topic)
      rows    = StreamSource.decodeRows(source)
      payloads = SessionPipeline.summaries(rows).map(_.toJson) // Dataset[String] → column "value"
      _      <- Console.printLine(
                  s"[spark-analytics] publishing game summaries '${RawGameEvent.Topic}' → '${GameSummary.Topic}'"
                )
      query  <- payloads.writeStream
                  .format("kafka")
                  .option("kafka.bootstrap.servers", bootstrap)
                  .option("topic", GameSummary.Topic)
                  .option("checkpointLocation", checkpoint)
                  .outputMode(OutputMode.Append())
                  .start
      _      <- ZIO.attemptBlocking(query.awaitTermination())
    yield ()

  private val session =
    SparkSession.builder
      .master(localAllNodes)
      .appName("pichess-spark-analytics-sink")
      .asLayer

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      bootstrap <- StreamSource.bootstrapFromEnv
      _         <- job(bootstrap).provide(session)
    yield ()

package chess.spark

import scala3encoders.given

import org.apache.spark.sql.streaming.OutputMode

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.agg.Aggregations
import chess.spark.schema.RawGameEvent
import chess.spark.stream.StreamSource

/** Event-time windowed streaming — counts events per type in tumbling windows
  * over the `occurredAt` event time, with a watermark bounding late-data state.
  *
  * Together with `GameSessionStreamMain` (stateful sessionization) this rounds
  * out the "advanced streaming" story: stateless windowed aggregation with
  * proper event-time + watermark semantics, beyond the stateless
  * processing-time `groupBy().count()` of `StreamAnalyticsMain`.
  */
object WindowedStreamMain extends ZIOAppDefault:

  private def job(bootstrap: String): ZIO[SparkSession, Throwable, Unit] =
    for
      source <- StreamSource.kafka(bootstrap, RawGameEvent.Topic)
      rows    = StreamSource.decodeRows(source)
      windowed = Aggregations.windowedEventCounts(
                   rows,
                   windowDuration = "5 seconds",
                   watermark = "10 seconds"
                 )
      _      <- Console.printLine(
                  s"[spark-analytics] windowed counts over '${RawGameEvent.Topic}' from $bootstrap"
                )
      query  <- windowed.writeStream
                  .format("console")
                  .option("truncate", value = false)
                  .outputMode(OutputMode.Update())
                  .start
      _      <- ZIO.attemptBlocking(query.awaitTermination())
    yield ()

  private val session =
    SparkSession.builder
      .master(localAllNodes)
      .appName("pichess-spark-windowed")
      .asLayer

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      bootstrap <- StreamSource.bootstrapFromEnv
      _         <- job(bootstrap).provide(session)
    yield ()

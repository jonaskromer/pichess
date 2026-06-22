package chess.spark

import scala3encoders.given

import org.apache.spark.sql.streaming.OutputMode

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.schema.RawGameEvent
import chess.spark.session.SessionPipeline
import chess.spark.stream.StreamSource

/** Stateful streaming — per-game sessionization via `flatMapGroupsWithState`.
  *
  * This is the "beyond textbook" streaming pattern: instead of a stateless
  * `groupBy().count()`, it keys the live event stream by `gameId` and folds
  * each game's events into running [[GameState]] that Spark persists across
  * micro-batches. When a terminal event arrives, it emits one [[GameSummary]]
  * (moves, duration, opening signature, result, avg think-time) and clears the
  * per-game state. Append output mode = each game surfaces exactly once, when
  * it completes.
  */
object GameSessionStreamMain extends ZIOAppDefault:

  private def job(bootstrap: String): ZIO[SparkSession, Throwable, Unit] =
    for
      source <- StreamSource.kafka(bootstrap, RawGameEvent.Topic)
      rows    = StreamSource.decodeRows(source)
      summaries = SessionPipeline.summaries(rows)
      _      <- Console.printLine(
                  s"[spark-analytics] sessionizing '${RawGameEvent.Topic}' from $bootstrap"
                )
      query  <- summaries.writeStream
                  .format("console")
                  .option("truncate", value = false)
                  .outputMode(OutputMode.Append())
                  .start
      _      <- ZIO.attemptBlocking(query.awaitTermination())
    yield ()

  private val session =
    SparkSession.builder
      .master(localAllNodes)
      .appName("pichess-spark-game-sessions")
      .asLayer

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      bootstrap <- StreamSource.bootstrapFromEnv
      _         <- job(bootstrap).provide(session)
    yield ()

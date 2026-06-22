package chess.spark

import scala3encoders.given

import org.apache.spark.sql.streaming.{GroupState, GroupStateTimeout, OutputMode}

import zio.*
import zio.spark.parameter.*
import zio.spark.sql.*

import chess.spark.schema.{MoveEventRow, RawGameEvent}
import chess.spark.session.{GameSessions, GameState, GameSummary}
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

  /** The stateful step: fold this micro-batch's events for one game into its
    * carried state; emit a summary (and drop the state) only once the game ends.
    */
  private def step(
      gameId: String,
      events: Iterator[MoveEventRow],
      state: GroupState[GameState]
  ): Iterator[GameSummary] =
    val updated = events.foldLeft(state.getOption.getOrElse(GameSessions.empty))(GameSessions.fold)
    if GameSessions.isComplete(updated) then
      state.remove()
      Iterator.single(GameSessions.summarize(gameId, updated))
    else
      state.update(updated)
      Iterator.empty

  private def job(bootstrap: String): ZIO[SparkSession, Throwable, Unit] =
    for
      source <- StreamSource.kafka(bootstrap, RawGameEvent.Topic)
      rows    = StreamSource.decodeRows(source)
      summaries = rows
                    .groupByKey(_.gameId)
                    .flatMapGroupsWithState[GameState, GameSummary](
                      OutputMode.Append(),
                      GroupStateTimeout.NoTimeout()
                    )(step)
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

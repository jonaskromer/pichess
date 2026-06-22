package chess.spark.session

import scala3encoders.given

import org.apache.spark.sql.streaming.{GroupState, GroupStateTimeout, OutputMode}

import zio.spark.sql.*

import chess.spark.schema.MoveEventRow

/** The stateful sessionization transform, shared by every job that needs
  * per-game summaries (console demo, Kafka loop-back sink, …). Keeps the
  * `flatMapGroupsWithState` wiring in one place so the [[GameSessions]] folding
  * logic is the single source of truth.
  */
object SessionPipeline:

  /** Fold this micro-batch's events for one game into carried state; emit a
    * summary (and drop the state) only once the game ends.
    */
  private def step(
      gameId: String,
      events: Iterator[MoveEventRow],
      state: GroupState[GameState]
  ): Iterator[GameSummary] =
    val updated =
      events.foldLeft(state.getOption.getOrElse(GameSessions.empty))(GameSessions.fold)
    if GameSessions.isComplete(updated) then
      state.remove()
      Iterator.single(GameSessions.summarize(gameId, updated))
    else
      state.update(updated)
      Iterator.empty

  /** Streaming `MoveEventRow`s → one `GameSummary` per completed game. */
  def summaries(rows: Dataset[MoveEventRow]): Dataset[GameSummary] =
    rows
      .groupByKey(_.gameId)
      .flatMapGroupsWithState[GameState, GameSummary](
        OutputMode.Append(),
        GroupStateTimeout.NoTimeout()
      )(step)

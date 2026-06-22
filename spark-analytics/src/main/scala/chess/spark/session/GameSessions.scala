package chess.spark.session

import zio.json.*

import chess.spark.schema.MoveEventRow

/** Per-game accumulator carried across micro-batches by Spark's
  * `flatMapGroupsWithState`. All fields primitive/`String` for encoder safety.
  */
final case class GameState(
    moves: Int,
    firstTs: Long,
    lastTs: Long,
    opening: String,
    result: String
)

/** The record emitted once, when a game completes. This is also the payload
  * published to the `chess.analytics` topic for the live web-ui panel — hence
  * the zio-json encoder.
  */
final case class GameSummary(
    gameId: String,
    totalMoves: Int,
    durationMs: Long,
    opening: String,
    result: String,
    avgThinkTimeMs: Double
)

object GameSummary:
  /** Speed-layer output topic — Spark publishes completed-game summaries here,
    * the gateway relays them to the web-ui over SSE. */
  val Topic: String = "chess.analytics"

  given JsonEncoder[GameSummary] = DeriveJsonEncoder.gen

/** Pure sessionization logic — folding a game's event stream into running state
  * and projecting a summary on completion. Kept Spark-free so it is unit
  * testable; the streaming wiring (`GameSessionStreamMain`) only supplies
  * `GroupState` plumbing around these functions.
  */
object GameSessions:

  /** How many opening plies (half-moves) to capture as the opening signature. */
  val OpeningPlies = 6

  /** Terminal event types that complete a game. */
  private val Terminal = Set("GameEnded", "Forfeited", "DrawClaimed")

  val empty: GameState = GameState(0, Long.MaxValue, Long.MinValue, "", "")

  /** Apply one event to the running state. */
  def fold(state: GameState, row: MoveEventRow): GameState =
    val isMove = row.eventType == "MoveMade"
    val moves  = if isMove then state.moves + 1 else state.moves
    val opening =
      if isMove && moves <= OpeningPlies then
        if state.opening.isEmpty then row.san else s"${state.opening} ${row.san}"
      else state.opening
    val result = if Terminal.contains(row.eventType) then row.eventType else state.result
    GameState(
      moves = moves,
      firstTs = math.min(state.firstTs, row.occurredAt),
      lastTs = math.max(state.lastTs, row.occurredAt),
      opening = opening,
      result = result
    )

  /** A game is done once a terminal event has set its result. */
  def isComplete(s: GameState): Boolean = s.result.nonEmpty

  /** Project the final state into the emitted summary. `avgThinkTimeMs` is the
    * wall-clock span divided by the number of move intervals — a real domain
    * metric derivable from `occurredAt` alone.
    */
  def summarize(gameId: String, s: GameState): GameSummary =
    val durationMs = if s.lastTs >= s.firstTs then s.lastTs - s.firstTs else 0L
    val intervals  = math.max(s.moves - 1, 1)
    GameSummary(
      gameId = gameId,
      totalMoves = s.moves,
      durationMs = durationMs,
      opening = s.opening,
      result = s.result,
      avgThinkTimeMs = durationMs.toDouble / intervals
    )

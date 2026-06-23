package chess.spark.agg

import scala3encoders.given

import zio.spark.sql.*

import chess.spark.domain.Fen
import chess.spark.schema.MoveEventRow

/** Chess-domain batch analytics over the archived event log — the visibly
  * clever, beyond-counting layer: a FEN-derived square-occupancy heatmap, an
  * opening → outcome breakdown (per-game sessionized), and think-time stats.
  */
object DomainAggregations:

  /** Opening signature length, in plies. */
  val OpeningPlies = 6

  /** One fully-reduced game: opening signature, terminal outcome, length,
    * and average think-time (wall-clock span / move intervals).
    */
  final case class GameStat(
      gameId: String,
      opening: String,
      outcome: String,
      moves: Int,
      thinkMs: Double
  )

  /** Square → how often it was occupied across all observed move positions.
    * The FEN-parsing "UDF" is [[Fen.occupiedSquares]] applied per row.
    */
  def squareOccupancy(rows: Dataset[MoveEventRow]): Dataset[(String, Long)] =
    rows
      .filter(_.eventType == "MoveMade")
      .flatMap(r => Fen.occupiedSquares(r.fen))
      .groupByKey(identity)
      .count

  /** Reduce each game to a [[GameStat]]. Events for a game arrive unordered
    * within the group, so sort by `occurredAt` before extracting the opening
    * and the terminal outcome.
    */
  def gameStats(rows: Dataset[MoveEventRow]): Dataset[GameStat] =
    rows.groupByKey(_.gameId).mapGroups { (gameId, events) =>
      val sorted  = events.toList.sortBy(_.occurredAt)
      val moves   = sorted.filter(_.eventType == "MoveMade")
      val opening = moves.take(OpeningPlies).map(_.san).mkString(" ")
      val outcome =
        sorted.reverseIterator.collectFirst {
          case r if r.outcome.nonEmpty => r.outcome
        }.getOrElse("unknown")
      val span    =
        if sorted.isEmpty then 0L
        else sorted.map(_.occurredAt).max - sorted.map(_.occurredAt).min
      val think   = if moves.size > 1 then span.toDouble / (moves.size - 1) else 0.0
      GameStat(gameId, opening, outcome, moves.size, think)
    }

  /** (opening, outcome) → number of games — the win/result distribution per
    * opening. Built from [[gameStats]] so it reuses one sessionization pass.
    */
  def openingOutcomes(stats: Dataset[GameStat]): Dataset[((String, String), Long)] =
    stats.groupByKey(s => (s.opening, s.outcome)).count

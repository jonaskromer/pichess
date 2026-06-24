package chess.analytics

import chess.api.AnalyticsSummaryDto

/** In-memory aggregate state for the analytics serving views, folded from the
  * `chess.analytics` stream of per-completed-game [[AnalyticsSummaryDto]]s
  * (produced by the Spark speed layer). Pure + immutable so the fold and the
  * derived queries are unit-testable without Kafka or any database — the
  * ClickHouse `move_events` table this replaces is gone (see ADR 022).
  */
final case class AnalyticsState(
    games: Long,
    totalMoves: Long,
    openings: Map[String, Long],
    // gameIds already folded in — Kafka/Spark are at-least-once and a restart
    // replays the topic, so dedup keeps counts correct under redelivery.
    seen: Set[String]
):

  /** The `limit` most-frequent openings, ties broken alphabetically. */
  def topOpenings(limit: Int): List[(String, Long)] =
    openings.toList.sortBy { case (opening, n) => (-n, opening) }.take(limit)

  /** Mean moves per game, or `None` when no game has completed yet. */
  def averagePlies: Option[Double] =
    if games == 0 then None else Some(totalMoves.toDouble / games)

object AnalyticsState:

  val empty: AnalyticsState = AnalyticsState(0L, 0L, Map.empty, Set.empty)

  /** Accumulate one completed-game summary, ignoring a gameId already seen. */
  def fold(state: AnalyticsState, s: AnalyticsSummaryDto): AnalyticsState =
    if state.seen.contains(s.gameId) then state
    else
      val key = if s.opening.isEmpty then "(no moves)" else s.opening
      state.copy(
        games = state.games + 1,
        totalMoves = state.totalMoves + s.totalMoves,
        openings = state.openings.updatedWith(key)(c => Some(c.getOrElse(0L) + 1)),
        seen = state.seen + s.gameId
      )

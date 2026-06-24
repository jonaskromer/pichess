package chess.analytics

import chess.api.AnalyticsSummaryDto

/** Running "leaderboard" records folded from completed-game summaries; surfaced
  * as Grafana gauges. (`0` = no game seen yet.) */
final case class Records(
    longestGameMoves: Int,
    shortestGameMoves: Int,
    mostCaptures: Int
)

object Records:
  val empty: Records = Records(0, 0, 0)

  def fold(r: Records, s: AnalyticsSummaryDto): Records =
    Records(
      longestGameMoves = math.max(r.longestGameMoves, s.totalMoves),
      shortestGameMoves =
        if r.shortestGameMoves == 0 || s.totalMoves < r.shortestGameMoves then s.totalMoves
        else r.shortestGameMoves,
      mostCaptures = math.max(r.mostCaptures, s.captures)
    )

package chess.analytics

/** Splits a completed game's terminal info into two **independent** dimensions,
  * because the raw `outcome` conflates them: a `Forfeited` event carries the
  * winning colour, while a `GameEnded` event carries an end-reason (Checkmate /
  * Timeout / …). Pure + tested; the metric emission lives in `AnalyticsMetrics`.
  *
  * @return `(winner, endReason)` where
  *   - winner   ∈ White | Black | Draw | Decisive | Unknown
  *   - endReason ∈ resignation | draw-claim | <GameEnded status> | other
  */
object Outcomes:

  def classify(result: String, outcome: String): (String, String) =
    result match
      case "Forfeited" =>
        (if outcome.nonEmpty then outcome else "Decisive", "resignation")
      case "DrawClaimed" =>
        ("Draw", "draw-claim")
      case "GameEnded" =>
        val reason = if outcome.nonEmpty then outcome else "other"
        val winner = if isDrawish(outcome) then "Draw" else "Decisive"
        (winner, reason)
      case other =>
        ("Unknown", if outcome.nonEmpty then outcome else other)

  private def isDrawish(s: String): Boolean =
    val l = s.toLowerCase
    l.contains("draw") || l.contains("stalemate")

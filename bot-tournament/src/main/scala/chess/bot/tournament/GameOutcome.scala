package chess.bot.tournament

import chess.model.piece.Color

/** Pure classification of a finished tournament game into the two independent
  * metric dimensions the dashboard splits on: our **result** (win/loss/draw,
  * relative to the colour we played) and the **termination reason**
  * (checkmate/resigned/timeout/stalemate/draw/…).
  *
  * Kept I/O-free so it's unit-tested without a live game; the metric-emission
  * glue is [[TournamentMetrics]].
  */
object GameOutcome:

  /** `result` ∈ win|loss|draw (from our perspective); `status` is the server's
    * termination string normalised to lowercase (empty → "unknown").
    */
  final case class Outcome(result: String, status: String)

  /** Classify a `gameEnd` (or terminal `gameState`): `winner` is `None` on a
    * draw, otherwise the winning colour; we win iff that colour is ours.
    */
  def classify(winner: Option[Color], status: String, ourColor: Color): Outcome =
    val result = winner match
      case None                     => "draw"
      case Some(c) if c == ourColor => "win"
      case Some(_)                  => "loss"
    val reason = status.trim.toLowerCase
    Outcome(result, if reason.isEmpty then "unknown" else reason)

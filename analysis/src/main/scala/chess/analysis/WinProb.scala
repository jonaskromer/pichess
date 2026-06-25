package chess.analysis

/** Centipawn ↔ win-probability conversions (the Lichess model). Move quality is
  * judged by **win-% drop** rather than raw centipawns — robust across eval
  * magnitudes (a 100cp swing matters far more near 0 than when already +900).
  */
object WinProb:

  /** Win probability as a percentage (0–100) for a centipawn score from that
    * side's point of view. 0cp → 50%, large advantage → ~100%.
    */
  def pct(cp: Int): Double =
    50.0 + 50.0 * (2.0 / (1.0 + math.exp(-0.00368208 * cp)) - 1.0)

  /** Per-move accuracy percentage (0–100) from the win-% lost on the move
    * (Lichess' fitted curve); clamped to [0, 100].
    */
  def accuracy(winPctLoss: Double): Double =
    val raw = 103.1668 * math.exp(-0.04354 * winPctLoss) - 3.1669
    math.max(0.0, math.min(100.0, raw))

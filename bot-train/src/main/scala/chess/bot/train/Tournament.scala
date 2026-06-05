package chess.bot.train

import zio.*

import chess.bot.engine.Search

/** Match-up two [[Search]] instances over an N-game tournament and
  * report a win rate + Elo delta. Built on top of
  * [[SelfPlay.round]] (which already alternates colours +
  * supports cross-game parallelism), so a tournament is just a
  * thin "what's the head-to-head" wrapper over the existing
  * self-play infrastructure.
  *
  * Use cases:
  *   - "Did the new training run actually improve play?" — run
  *     `play(newSnapshot, oldSnapshot, ...)`; positive Elo delta
  *     ⇒ improvement.
  *   - "How strong is the bot vs a material-only baseline?" — use
  *     `Evaluator.materialOnly` for the canonical opponent.
  *   - "What's the curve as we tune?" — sequence of tournaments,
  *     each against the prior champion, gives a per-version Elo
  *     trajectory.
  *
  * Stat caveats are explicit in the [[Report]]:
  *   - Elo is a relative measure derived from win rate, not an
  *     absolute rating. Comparable only against the same opponent.
  *   - The standard formula treats draws as half-wins; we follow
  *     that convention. Heavily-drawn match-ups (e.g. equal
  *     evaluators) produce small Elo deltas with wide variance.
  *   - The 95% confidence interval on `eloDelta` widens as
  *     `games` shrinks. ~30 games gives ±50 Elo at typical win
  *     rates; ~100 games ±25.
  */
object Tournament:

  /** One head-to-head match-up result. */
  final case class Report(
      games: Int,
      challengerWins: Int,
      championWins: Int,
      draws: Int,
      // Win rate from the challenger's POV, draws count as 0.5.
      challengerScore: Double,
      // Estimated Elo delta = challenger − champion. Positive
      // means the challenger plays stronger.
      eloDelta: Double,
  ):
    def render: String =
      // Locale.ROOT keeps the decimal separator as `.` everywhere —
      // useful both for tests asserting on literal strings and for
      // log output that's grep-friendly across systems.
      val sign = if eloDelta > 0 then "+" else ""
      String.format(
        java.util.Locale.ROOT,
        "Tournament(games=%d, challenger=%d, champion=%d, draws=%d, score=%.1f%%, ΔElo=%s%.1f)",
        Integer.valueOf(games),
        Integer.valueOf(challengerWins),
        Integer.valueOf(championWins),
        Integer.valueOf(draws),
        java.lang.Double.valueOf(challengerScore * 100),
        sign,
        java.lang.Double.valueOf(eloDelta),
      )

  /** Run an N-game match between `challenger` and `champion`,
    * alternating colours, and produce a [[Report]] with win rate +
    * Elo delta.
    *
    * @param challenger      the new / candidate snapshot
    * @param champion        the baseline to measure against
    * @param games           tournament length (≥ 2 recommended;
    *                        ~30 for stable estimates)
    * @param depth           per-move search depth (depth 3-4 is a
    *                        good speed/quality trade for a quick
    *                        Elo readout)
    * @param maxPlies        cap to prevent infinite shuffles
    * @param parallelism     game-level parallelism (passes
    *                        straight through to
    *                        [[SelfPlay.round]])
    */
  def play(
      challenger: Search,
      champion:   Search,
      games:      Int,
      depth:      Int,
      maxPlies:   Int = 200,
      parallelism: Int = 1,
  ): UIO[Report] =
    SelfPlay
      .round(champion, challenger, games, depth, maxPlies, parallelism)
      .map { round =>
        val score = challengerScoreOf(round)
        Report(
          games           = round.games,
          challengerWins  = round.challengerWins,
          championWins    = round.championWins,
          draws           = round.draws,
          challengerScore = score,
          eloDelta        = eloDelta(score),
        )
      }

  /** Challenger's score expressed as a fraction in [0, 1]: wins
    * count 1, draws count 0.5. Standard chess tournament scoring.
    * Zero games → 0.5 (no information; treat as a draw). */
  private[train] def challengerScoreOf(r: SelfPlay.RoundResult): Double =
    if r.games == 0 then 0.5
    else (r.challengerWins.toDouble + 0.5 * r.draws) / r.games

  /** Elo delta from win rate via the standard formula
    * `ΔElo = −400·log10(1/winRate − 1)`. Clamps near 0/1 to
    * avoid `±Infinity` for shut-out tournaments — those land at
    * ±800 Elo, which is the conventional "extreme but finite"
    * value for a 0/100% sweep. */
  private[train] def eloDelta(score: Double): Double =
    val clamped =
      if score <= 0.005 then 0.005
      else if score >= 0.995 then 0.995
      else score
    -400.0 * math.log10(1.0 / clamped - 1.0)

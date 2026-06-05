package chess.bot.train

import zio.*
import zio.test.*

import chess.bot.engine.{Evaluator, Search}

/** Unit tests for [[Tournament]] — Elo math + score aggregation. */
object TournamentSpec extends ZIOSpecDefault:

  private val materialSearch: Search =
    Search.alphaBeta(Evaluator.materialOnly)

  def spec = suite("Tournament")(
    suite("Elo math")(
      test("50% win rate → 0 Elo delta") {
        // The reference point: equal opponents have ΔElo = 0.
        assertTrue(math.abs(Tournament.eloDelta(0.5)) < 1e-9)
      },
      test("65% win rate → ≈ +109 Elo delta (classic table value)") {
        // From any standard Elo table: 65% expected score ≈ +109.
        val delta = Tournament.eloDelta(0.65)
        assertTrue(math.abs(delta - 107.5) < 2.0)
      },
      test("100% win rate clamps to +800 (no infinity)") {
        // Without clamping the formula would emit +Infinity.
        // We saturate around ±800 for shut-out tournaments —
        // standard engine-tournament convention.
        val delta = Tournament.eloDelta(1.0)
        assertTrue(delta > 700.0 && delta < 1000.0)
      },
      test("0% win rate clamps to about -800") {
        val delta = Tournament.eloDelta(0.0)
        assertTrue(delta < -700.0 && delta > -1000.0)
      },
      test("Elo delta is symmetric around 0.5") {
        val above = Tournament.eloDelta(0.7)
        val below = Tournament.eloDelta(0.3)
        assertTrue(math.abs(above + below) < 1e-9)
      },
    ),
    suite("score aggregation")(
      test("0-game round scores 0.5 (no information)") {
        val empty = SelfPlay.RoundResult(0, 0, 0, 0, Vector.empty)
        assertTrue(Tournament.challengerScoreOf(empty) == 0.5)
      },
      test("all challenger wins → score 1.0") {
        val r = SelfPlay.RoundResult(4, challengerWins = 4, championWins = 0, draws = 0, Vector.empty)
        assertTrue(Tournament.challengerScoreOf(r) == 1.0)
      },
      test("draws count as 0.5") {
        // 1 challenger win + 2 draws + 1 loss in 4 games → 0.5.
        val r = SelfPlay.RoundResult(4, challengerWins = 1, championWins = 1, draws = 2, Vector.empty)
        assertTrue(Tournament.challengerScoreOf(r) == 0.5)
      },
    ),
    suite("Report.render")(
      test("renders a summary string with all key fields") {
        val report = Tournament.Report(
          games = 10,
          challengerWins = 6, championWins = 3, draws = 1,
          challengerScore = 0.65, eloDelta = 107.5,
        )
        val s = report.render
        assertTrue(
          s.contains("games=10"),
          s.contains("challenger=6"),
          s.contains("champion=3"),
          s.contains("draws=1"),
          s.contains("65.0%"),
          s.contains("ΔElo=+107.5"),
        )
      },
      test("negative Elo delta renders without a '+' sign") {
        val r = Tournament.Report(
          games = 4, challengerWins = 1, championWins = 3, draws = 0,
          challengerScore = 0.25, eloDelta = -191.0,
        )
        assertTrue(r.render.contains("ΔElo=-191.0"))
      },
    ),
    suite("end-to-end play")(
      test("identical search → expected ~50% win rate (every game a draw at depth 1)") {
        for report <- Tournament.play(
                        challenger = materialSearch,
                        champion   = materialSearch,
                        games      = 2, depth = 1, maxPlies = 4,
                      )
        yield assertTrue(
          report.games == 2,
          // depth 1 + maxPlies 4 → both games hit the cap → draws.
          // challengerScore = 0.5 either way (draws split 50/50).
          math.abs(report.challengerScore - 0.5) < 0.01,
        )
      },
      test("default maxPlies still terminates (covers the default-arg branch)") {
        // 0-game tournament short-circuits — exercises the API
        // signature without playing actual games, so we can rely
        // on the default `maxPlies = 200` without pulling in a
        // slow full-game cap-test.
        for report <- Tournament.play(
                        challenger = materialSearch,
                        champion   = materialSearch,
                        games      = 0,
                        depth      = 1,
                      )
        yield assertTrue(report.games == 0)
      },
    ),
  )

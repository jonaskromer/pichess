package chess.bot.lichess

import zio.test.*

object TimeManagerSpec extends ZIOSpecDefault:

  def spec = suite("TimeManager.budgetMs")(
    test("blitz 3+2: a sensible few seconds, within the share cap") {
      val b = TimeManager.budgetMs(remainingMs = 180_000, incMs = 2_000)
      assertTrue(
        b >= 4_000,
        b <= 8_000,
        b <= 180_000 * TimeManager.MaxPercent / 100, // share cap respected
      )
    },
    test("NEVER spends past the safety buffer (no flagging)") {
      // across a sweep of low-ish clocks, budget <= remaining - buffer
      val checks = for r <- List(150L, 300L, 1_000L, 5_000L, 60_000L) yield
        TimeManager.budgetMs(r, 0) <= r - TimeManager.SafetyBufferMs || r <= TimeManager.SafetyBufferMs + TimeManager.MinBudgetMs
      assertTrue(checks.forall(identity))
    },
    test("deep time pressure → tiny emergency budget, still positive") {
      val b = TimeManager.budgetMs(remainingMs = 120, incMs = 0)
      assertTrue(b == 10L)
    },
    test("more time → at least as much budget (monotonic)") {
      assertTrue(TimeManager.budgetMs(300_000, 0) >= TimeManager.budgetMs(60_000, 0))
    },
    test("long/correspondence clock is capped for responsiveness") {
      assertTrue(TimeManager.budgetMs(10_000_000, 0) == TimeManager.MaxBudgetMs)
    },
    test("a large increment is still bounded by the share cap (won't blow the clock)") {
      val b = TimeManager.budgetMs(remainingMs = 10_000, incMs = 5_000)
      assertTrue(b == 10_000 * TimeManager.MaxPercent / 100)
    },
    // ── Adaptive factors (5-arg form) ──────────────────────────────────
    test("the 2-arg form equals the neutral 5-arg form (backward compatible)") {
      assertTrue(
        TimeManager.budgetMs(180_000, 2_000) ==
          TimeManager.budgetMs(180_000, 2_000, -1L, 1.0, false),
        TimeManager.budgetMs(10_000, 5_000) ==
          TimeManager.budgetMs(10_000, 5_000, -1L, 1.0, false),
      )
    },
    test("middlegame gets more search time than the opening (same clock)") {
      val mid  = TimeManager.budgetMs(120_000, 2_000, 120_000, phase = 0.5, inCheck = false)
      val open = TimeManager.budgetMs(120_000, 2_000, 120_000, phase = 1.0, inCheck = false)
      assertTrue(mid > open)
    },
    test("ahead on the clock spends more than behind (same position)") {
      val ahead  = TimeManager.budgetMs(120_000, 2_000, oppRemainingMs = 20_000, phase = 1.0, inCheck = false)
      val behind = TimeManager.budgetMs(120_000, 2_000, oppRemainingMs = 600_000, phase = 1.0, inCheck = false)
      assertTrue(ahead > behind)
    },
    test("a check is given more time than a quiet position") {
      val chk   = TimeManager.budgetMs(120_000, 2_000, 120_000, phase = 0.5, inCheck = true)
      val quiet = TimeManager.budgetMs(120_000, 2_000, 120_000, phase = 0.5, inCheck = false)
      assertTrue(chk > quiet)
    },
    test("all factors maxed still never flags (budget <= safe)") {
      val checks = for r <- List(2_000L, 5_000L, 20_000L, 120_000L) yield
        val b = TimeManager.budgetMs(r, 0, oppRemainingMs = 1L, phase = 0.5, inCheck = true)
        b <= r - TimeManager.SafetyBufferMs ||
          r <= TimeManager.SafetyBufferMs + TimeManager.MinBudgetMs
      assertTrue(checks.forall(identity))
    },
  )

package chess.bot.engine

/** Turns the remaining clock into a per-move search budget (roadmap #5).
  *
  * The bot used to think a flat 2 s/move regardless of the clock — which both
  * over-thinks in long controls and, worse, risks **flagging** (losing on time,
  * an outright loss regardless of position) in fast ones. This sizes each move
  * to the time actually left.
  *
  * Lives in `bot-engine` (not any one protocol adapter) because the budgeting
  * is pure, engine-side time management shared by every bot front-end — the
  * Lichess bridge (`bot-lichess`) and the NowChess tournament bridge
  * (`bot-tournament`) both size their moves through it.
  *
  * Formula, re-evaluated every move so it's self-correcting:
  * {{{
  *   budget = remaining / MovesToGo + (IncNum/IncDen)·increment
  * }}}
  * clamped so it can never spend a dangerous share of the clock. With any
  * increment the budget self-stabilises around `remaining ≈ MovesToGo · share
  * of inc` (drain → 0) and never flags; in sudden-death it decays geometrically
  * toward the floor.
  */
object TimeManager:

  val MovesToGo = 35 // assumed moves left when nothing else is known
  val IncNum = 4 // spend IncNum/IncDen of the increment ...
  val IncDen = 5 // ... i.e. 80%
  val MaxPercent = 15 // never spend >15% of the clock on one move
  val MinBudgetMs = 50L // always think at least this long
  val SafetyBufferMs = 300L // reserve for move-POST network latency
  val MaxBudgetMs = 12_000L // responsiveness cap (long / correspondence)

  // -- Adaptive factors -------------------------------------------------
  // The base formula is deliberately conservative, so it BANKS time. These
  // multipliers spend that banked time where it matters; the hard clamps
  // below keep every result flag-safe (the base spends a fixed share of the
  // clock, decaying geometrically, so scaling it by <= MultMax never reaches
  // zero — we cannot time out, only think harder on the moves that count).
  val MiddlegameBoost = 0.35 // up to +35% search time in the middlegame
  val AdvMin = 0.70 // behind on the clock -> conserve
  val AdvMax = 1.60 // ahead on the clock  -> spend the surplus
  val CheckBoost = 1.40 // side-to-move in check -> forcing, must calculate
  val MultMin = 0.65 // combined-multiplier floor
  val MultMax = 1.80 // ...and cap (keeps effective moves-to-go >= ~19)

  /** Backward-compatible budget: no opponent clock, neutral stage, not in check
    * (reproduces the original conservative formula exactly).
    */
  def budgetMs(remainingMs: Long, incMs: Long): Long =
    budgetMs(
      remainingMs,
      incMs,
      oppRemainingMs = -1L,
      phase = 1.0,
      inCheck = false
    )

  /** Per-move search budget, adaptive to game stage, clock advantage, and check
    * — banked time is spent on the moves that actually decide games.
    *
    * @param remainingMs
    *   our remaining clock, ms
    * @param incMs
    *   our increment per move, ms
    * @param oppRemainingMs
    *   opponent's remaining clock, ms (<= 0 = unknown)
    * @param phase
    *   game phase 1.0 (opening) .. 0.0 (bare endgame), e.g. from
    *   [[chess.bot.engine.GamePhase]]
    * @param inCheck
    *   true when the side to move is in check
    *
    * Factors scale the conservative base; then hard clamps guarantee we never
    * flag:
    *   - STAGE: the middlegame rewards deep search most; the opening is
    *     book-like and the deep endgame has fewer candidate moves (+ TB), so
    *     both get less.
    *   - CLOCK ADVANTAGE: time banked over the opponent is wasted strength —
    *     spend it when ahead, conserve when behind.
    *   - CHECK: a check is forcing and a wrong reply often loses outright, so
    *     invest more.
    */
  def budgetMs(
      remainingMs: Long,
      incMs: Long,
      oppRemainingMs: Long,
      phase: Double,
      inCheck: Boolean
  ): Long =
    val safe = remainingMs - SafetyBufferMs
    if safe <= MinBudgetMs then
      // Deep time pressure: spend a small slice, just don't flag.
      math.max(10L, remainingMs / 20)
    else
      val base = remainingMs / MovesToGo + incMs * IncNum / IncDen
      val stageMult =
        1.0 + MiddlegameBoost * (1.0 - math.abs(phase - 0.5) * 2.0)
      val advMult =
        if oppRemainingMs <= 0L then 1.0
        else
          clampD(remainingMs.toDouble / oppRemainingMs.toDouble, AdvMin, AdvMax)
      val checkMult = if inCheck then CheckBoost else 1.0
      val mult = clampD(stageMult * advMult * checkMult, MultMin, MultMax)
      math
        .round(base * mult)
        .min(remainingMs * MaxPercent / 100) // share cap — don't blow the clock
        .min(MaxBudgetMs) // responsiveness cap
        .min(safe) // never risk the clock
        .max(MinBudgetMs) // but always think a little

  private def clampD(x: Double, lo: Double, hi: Double): Double =
    math.max(lo, math.min(hi, x))

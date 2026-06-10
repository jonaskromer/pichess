package chess.bot.lichess

/** Turns the remaining clock into a per-move search budget (roadmap #5).
  *
  * The bot used to think a flat 2 s/move regardless of the clock — which both
  * over-thinks in long controls and, worse, risks **flagging** (losing on
  * time, an outright loss regardless of position) in fast ones. This sizes
  * each move to the time actually left.
  *
  * Formula, re-evaluated every move so it's self-correcting:
  * {{{
  *   budget = remaining / MovesToGo + (IncNum/IncDen)·increment
  * }}}
  * clamped so it can never spend a dangerous share of the clock. With any
  * increment the budget self-stabilises around `remaining ≈ MovesToGo · share
  * of inc` (drain → 0) and never flags; in sudden-death it decays
  * geometrically toward the floor. */
object TimeManager:

  val MovesToGo      = 35      // assumed moves left — higher = more conservative
  val IncNum         = 4       // spend IncNum/IncDen of the increment …
  val IncDen         = 5       // … i.e. 80%
  val MaxPercent     = 15      // never spend >15% of the clock on one move
  val MinBudgetMs    = 50L     // always think at least this long
  val SafetyBufferMs = 300L    // reserve for move-POST network latency — never flag
  val MaxBudgetMs    = 12_000L // responsiveness cap (long / correspondence)

  /** @param remainingMs our side's remaining clock, ms
    * @param incMs       our side's increment per move, ms */
  def budgetMs(remainingMs: Long, incMs: Long): Long =
    val safe = remainingMs - SafetyBufferMs
    if safe <= MinBudgetMs then
      // Deep time pressure: spend a small slice, just don't flag.
      math.max(10L, remainingMs / 20)
    else
      val base = remainingMs / MovesToGo + incMs * IncNum / IncDen
      base
        .min(remainingMs * MaxPercent / 100) // share cap — don't blow the clock
        .min(MaxBudgetMs)                    // responsiveness cap
        .min(safe)                           // never risk the clock
        .max(MinBudgetMs)                    // but always think a little

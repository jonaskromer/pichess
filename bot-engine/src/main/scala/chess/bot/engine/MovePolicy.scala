package chess.bot.engine

import zio.{Random, UIO}

import chess.model.board.{GameState, Move}

/** Turns a [[Difficulty]] into an actual bot move over a [[Search]].
  *
  * This is the one place that reads a difficulty's effort knobs and decides
  * *how* to search: the weak levels run a shallow fixed-depth search and may
  * deliberately play an off-best move ([[Difficulty.noise]]); the harder levels
  * run production-style time-budgeted iterative deepening so they genuinely
  * think longer (and deeper) per move. The game-service vs-bot handler calls
  * [[select]] instead of poking the search directly.
  */
object MovePolicy:

  /** How many of the top-ranked moves the noisy levels sample a blunder from. */
  inline val NoiseTopK = 3

  /** Pick the move a level plays from a ranked candidate list (best first).
    *
    * With probability `noise` it "blunders" — picks uniformly among the top
    * [[NoiseTopK]] candidates rather than the best — which is what makes a
    * Beginner feel like a beginner instead of a flawless-but-shallow engine.
    * `blunderRoll` / `choiceRoll` are independent uniform draws in `[0, 1)`;
    * pure so it's unit-testable without an engine.
    */
  def chooseNoisy[A](
      ranked: List[(A, Int)],
      noise: Double,
      blunderRoll: Double,
      choiceRoll: Double
  ): Option[A] =
    ranked match
      case Nil => None
      case (best, _) :: _ =>
        if blunderRoll >= noise then Some(best)
        else
          val k   = math.min(NoiseTopK, ranked.size)
          val idx = math.min(k - 1, (choiceRoll * k).toInt)
          Some(ranked(idx)._1)

  /** Run one bot move for `difficulty`.
    *
    *   - budget `0` → fixed-depth search to `difficulty.maxDepth`, then a
    *     noise roll: instant, weak, human-ish.
    *   - budget `> 0` → time-budgeted iterative deepening capped at
    *     `difficulty.maxDepth`: the bot thinks for ~`budgetMs` and goes as deep
    *     as that buys, at full strength (no noise).
    */
  def select(
      search: Search,
      state: GameState,
      difficulty: Difficulty,
      history: Set[Long]
  ): UIO[Option[Move]] =
    if difficulty.budgetMs <= 0 then
      for
        ranked  <- search.bestMoves(state, difficulty.maxDepth, NoiseTopK, history)
        blunder <- Random.nextDouble
        choice  <- Random.nextDouble
      yield chooseNoisy(ranked, difficulty.noise, blunder, choice)
    else
      search.bestMoveWithBudget(
        state,
        difficulty.budgetMs,
        history,
        maxDepth = difficulty.maxDepth
      )

package chess.bot.engine

import zio.json.*

/** Difficulty levels for vs-bot gameplay.
  *
  * Each level is an *effort budget* over the production engine — the same
  * hybrid HCE+NNUE evaluator and α-β search the live Lichess / tournament bot
  * uses, just throttled. Three knobs:
  *
  *   - [[budgetMs]]: per-move wall-clock budget. `0` means "no clock — search a
  *     fixed [[maxDepth]] and return", which keeps the weak levels instant. Any
  *     positive value switches the level to time-budgeted iterative deepening
  *     ([[Search.bestMoveWithBudget]]), so a harder level genuinely *thinks
  *     longer* (and therefore deeper) per move.
  *   - [[maxDepth]]: a hard depth ceiling. For the budgeted levels it keeps a
  *     tier in its lane regardless of how fast the host CPU is (a quick machine
  *     can't turn "Medium" into a depth-12 monster); for the fixed-depth levels
  *     it *is* the search depth.
  *   - [[noise]]: 0–1 chance of playing a non-top move (sampled from the top
  *     [[MovePolicy.NoiseTopK]]). Lets the weak levels blunder like a human
  *     rather than playing flawlessly-but-shallow. Only meaningful on the
  *     fixed-depth levels; the budgeted levels run at full strength.
  *
  * The caller (game-service's vs-bot handler) runs a level through
  * [[MovePolicy.select]], which reads these fields — the search itself never
  * sees [[Difficulty]] directly.
  */
enum Difficulty(
    val maxDepth: Int,
    val budgetMs: Long,
    val noise: Double
):

  /** Near-random — depth 2 sees little past direct captures, and a 30% chance
    * of an off-best move adds the blunders a real beginner makes. Instant.
    */
  case Beginner extends Difficulty(maxDepth = 2, budgetMs = 0, noise = 0.30)

  /** Solid threat-spotting but no plans — a few ply, with the odd slip (10%).
    * Instant.
    */
  case Easy extends Difficulty(maxDepth = 3, budgetMs = 0, noise = 0.10)

  /** Reads short tactics, plays clean casual-club chess. A small per-move
    * budget so it feels responsive but no longer instant; capped at depth 7 so
    * it stays "medium" on any hardware.
    */
  case Medium extends Difficulty(maxDepth = 7, budgetMs = 400, noise = 0.0)

  /** Three-to-four-move tactics + steady positional play. Thinks ~1s/move and
    * beats unaided casual players consistently.
    */
  case Hard extends Difficulty(maxDepth = 14, budgetMs = 1_000, noise = 0.0)

  /** Strong amateur — thinks ~2s/move, reaching well past the old depth-5
    * ceiling. Responsive enough for back-and-forth play.
    */
  case Expert extends Difficulty(maxDepth = 32, budgetMs = 2_000, noise = 0.0)

  /** The full production engine: a long ~6s budget with LazySMP across spare
    * cores (the exact config the live ~2350-Elo bot plays at). The bot really
    * sits and calculates — expect a few seconds per move. `maxDepth = 128` is
    * effectively uncapped (past the engine's own MaxPly the ID loop stops
    * anyway), so the wall clock alone bounds the search.
    */
  case Max extends Difficulty(maxDepth = 128, budgetMs = 6_000, noise = 0.0)

object Difficulty:

  given JsonEncoder[Difficulty] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[Difficulty] =
    JsonDecoder[String].mapOrFail(s =>
      Difficulty.values
        .find(_.toString.equalsIgnoreCase(s))
        .toRight(
          s"Unknown difficulty: '$s' (expected one of: ${Difficulty.values.map(_.toString).mkString(", ")})"
        )
    )

  /** Default difficulty when none is specified — Medium gives a playable
    * opponent without instantly mating casual players.
    */
  val Default: Difficulty = Medium

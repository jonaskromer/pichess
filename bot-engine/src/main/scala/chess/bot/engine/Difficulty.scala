package chess.bot.engine

import zio.json.*

/** Coarse-grained difficulty levels for vs-bot gameplay.
  *
  * The primary knob is [[searchDepth]] — α-β at depth 1 sees only immediate
  * replies (essentially "blunder unless there's a hanging piece"), depth 4-5
  * plays solid chess, and depth 8+ punishes most club players. Adding a 0–1
  * [[noise]] level lets us simulate human blunders at the lowest difficulties
  * without inflating depth: the search picks the Nth best move with probability
  * `noise` (where N is uniformly sampled from the top-3).
  *
  * Wired into [[Search.bestMove]] via `depth = difficulty.searchDepth` — search
  * itself doesn't see [[Difficulty]] directly; the caller (game-service's
  * vs-bot handler) maps difficulty → depth.
  *
  * Noise is not used in Phase-1 of the integration — depth alone gives a wide
  * enough skill spread for a starter bot. The field is here so later phases can
  * plug in randomness without changing the enum's wire shape.
  */
enum Difficulty(val searchDepth: Int, val noise: Double):

  /** Random-ish play — depth 1 sees nothing past direct captures, and a 30%
    * chance of picking a non-top move adds chaos. Suitable for a child or
    * someone learning the rules.
    */
  case Beginner extends Difficulty(searchDepth = 1, noise = 0.3)

  /** Solid threat-spotting but no plans — sees one or two ply ahead. Will miss
    * two-move combinations.
    */
  case Easy extends Difficulty(searchDepth = 2, noise = 0.1)

  /** Reads short tactics — sees two-move forks, captures with follow-ups. Plays
    * at a casual club level.
    */
  case Medium extends Difficulty(searchDepth = 3, noise = 0.0)

  /** Three-move tactics + steady positional play. Beats unaided casual players
    * consistently.
    */
  case Hard extends Difficulty(searchDepth = 4, noise = 0.0)

  /** Multi-ply tactics + alpha-beta cutoffs really start cooking at depth 5.
    * Reaches strong-amateur strength on a Texel-tuned eval.
    */
  case Expert extends Difficulty(searchDepth = 5, noise = 0.0)

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

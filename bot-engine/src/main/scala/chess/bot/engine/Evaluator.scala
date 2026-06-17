package chess.bot.engine

import chess.bot.engine.nnue.{NnueAccumulator, NnueEvaluator}
import chess.model.board.{GameState, PositionView}

/** Static position evaluator.
  *
  * Returns a score in centipawns from the white-side perspective: positive
  * means white is winning, negative means black is winning. The search layer
  * flips signs as needed via negamax — evaluators don't know about whose turn
  * it is, just about who has more on the board.
  *
  * Phase 1 has a single material-only implementation
  * ([[Evaluator.materialOnly]]). The trait deliberately stays minimal so later
  * phases can swap in a Texel-tuned linear combination of features
  * (piece-square tables, mobility, pawn structure, king safety, …) without
  * touching the search code.
  */
trait Evaluator:
  def evaluate(state: PositionView): Int

  /** Component breakdown of the eval — returns named contributions (e.g.,
    * material / mobility / king-safety) so training data can emit each
    * component alongside the total. Used by NnueDataGen for the per-row `comps`
    * column, which enables future bucketed / multi-head NNUE training.
    *
    * Default implementation returns just the total under key `"total"`.
    * Decomposed evaluators (e.g., the tapered HCE) override with finer-grained
    * breakdowns.
    */
  def evaluateComponents(state: GameState): Map[String, Int] =
    Map("total" -> evaluate(state))

  /** The NNUE behind this evaluator, if any. When present, the search maintains
    * an [[NnueAccumulator]] incrementally — refreshed at the root, then ±only
    * the changed feature columns per move — and evaluates leaves via
    * [[evaluateWith]], which is far cheaper than rebuilding the accumulator on
    * every call. `None` ⇒ no accumulator path; the search falls back to
    * [[evaluate]].
    */
  def incrementalNet: Option[NnueEvaluator] = None

  /** Leaf eval that reads a search-maintained accumulator for the NNUE
    * component. The default ignores `acc` and does a full [[evaluate]] (correct
    * for evaluators without an NNUE half).
    */
  def evaluateWith(acc: NnueAccumulator, state: PositionView): Int =
    evaluate(state)

object Evaluator:

  /** Material-only evaluator. Standard centipawn values: pawn 100, knight 320,
    * bishop 330, rook 500, queen 900.
    *
    * The king is worth 0 — checkmate detection happens in the search via
    * `MoveValidator.isInCheck` + empty-legal-moves combination, not via a
    * finite king value that would distort eval. Material values are the classic
    * Larry Kaufman set used by most starter engines.
    *
    * Implementation reads the per-piece bitboards' `popCount` directly, so each
    * call is ~6 bitcount instructions per side — sub-µs.
    */
  val materialOnly: Evaluator = MaterialEvaluator

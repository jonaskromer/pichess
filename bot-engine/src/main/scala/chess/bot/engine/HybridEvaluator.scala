package chess.bot.engine

import chess.bot.engine.nnue.{NnueAccumulator, NnueEvaluator}
import chess.model.board.GameState

/** Blends two evaluators into one score: `(1 − w)·base + w·other`.
  *
  * Motivation: a strong NNUE strictly dominates a hand-crafted eval
  * (HCE), so production engines go pure-NNUE. But when the NNUE is
  * weaker than the HCE (e.g. small / undertrained), a blend weighted
  * toward the stronger eval can still beat either alone IF the two
  * make *decorrelated* errors — the classic weak-learner-helps-
  * ensemble effect. This evaluator makes that hypothesis testable
  * without touching the search: it's just another [[Evaluator]].
  *
  * `nnueWeight` is the mixing weight on `other` (the NNUE), in
  * `[0, 1]`. 0.0 → pure base (HCE); 1.0 → pure other (NNUE); 0.25 →
  * mostly HCE with a quarter-strength NNUE nudge. Both sub-evals
  * return white-POV centipawns, so the blend is in the same units —
  * no rescaling needed.
  *
  * Cost is the sum of both sub-evals per call (HCE is cheap; the
  * NNUE dominates), so a blend is roughly as expensive as pure
  * NNUE. If that proves too slow for the depth it buys, the next
  * step is a *lazy* blend — skip the NNUE when the HCE is already
  * far outside the search window. */
final class HybridEvaluator(
    base: Evaluator,
    other: Evaluator,
    nnueWeight: Double,
) extends Evaluator:

  private val w  = math.max(0.0, math.min(1.0, nnueWeight))
  private val bw = 1.0 - w

  // The NNUE half, when `other` is one — enables the incremental
  // accumulator path. EngineBundle / loadSearch always pass an NNUE here;
  // a non-NNUE `other` simply disables incremental (full eval instead).
  private val net: Option[NnueEvaluator] = other match
    case n: NnueEvaluator => Some(n)
    case _                => None

  override def evaluate(state: GameState): Int =
    val b = base.evaluate(state)
    val o = other.evaluate(state)
    math.round(bw * b + w * o).toInt

  // -- Incremental-eval capability (see [[Evaluator]]). The HCE half is
  //    computed from `state`; the NNUE half reads the maintained acc. --
  override def incrementalNet: Option[NnueEvaluator] = net
  override def evaluateWith(acc: NnueAccumulator, state: GameState): Int =
    net match
      case Some(n) =>
        math.round(bw * base.evaluate(state) + w * n.evaluateFrom(acc, state.activeColor)).toInt
      case None =>
        evaluate(state)

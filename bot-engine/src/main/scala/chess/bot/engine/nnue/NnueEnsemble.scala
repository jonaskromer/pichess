package chess.bot.engine.nnue

import chess.bot.engine.Evaluator
import chess.model.board.GameState

/** Ensemble of K NNUE evaluators — averages their `evaluate`
  * results. Variance reduction via the standard "wisdom of K
  * regressors" effect: if each net has independent prediction
  * noise, averaging K of them shrinks the noise by `1/sqrt(K)`
  * without changing the mean.
  *
  * Practically: the K members must have been trained with
  * different random seeds (different init + different batch
  * ordering) so their errors decorrelate. K=3-5 is the usual
  * sweet spot — more members give diminishing returns and K×
  * the inference cost.
  *
  * Inference cost is exactly K× the single-net cost: the
  * accumulators don't share weights (each member has its own
  * 768×128 feature matrix), so there's no amortization. Worth
  * paying at higher search depths where eval is a smaller
  * fraction of total cost. */
final class NnueEnsemble private (
    private val members: Array[NnueEvaluator],
) extends Evaluator:

  override def evaluate(state: GameState): Int =
    var total = 0L
    var i = 0
    while i < members.length do
      total += members(i).evaluate(state)
      i += 1
    (total / members.length).toInt

object NnueEnsemble:

  /** Load K ensemble members from the given resource paths. Returns
    * None if any resource is missing (caller should fall back to a
    * single-net or HCE evaluator). */
  def loadResources(names: Iterable[String]): Option[NnueEnsemble] =
    val loaded = names.toVector.flatMap(n => NnueEvaluator.loadResource(n).toList)
    if loaded.isEmpty || loaded.size != names.size then None
    else Some(new NnueEnsemble(loaded.toArray))

  /** Convenience: load the baked `/nnue-ens-v1-s{1..K}.bin` files
    * shipped with the engine resources. K controls how many members
    * to read; missing files cause `None`. */
  def loadBaked(k: Int = 3): Option[NnueEnsemble] =
    loadResources((1 to k).map(i => s"/nnue-ens-v1-s$i.bin"))

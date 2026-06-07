package chess.bot.engine.mcts

/** MCTS + policy network roadmap.
  *
  * NOT a working implementation — this file collects the design
  * notes that the next session(s) will turn into code. The α-β
  * search we ship today does well at depth 4-6 (~1500-1700 Elo).
  * MCTS is the Lc0/AlphaZero path that pushes past ~2500 Elo at
  * the cost of an architectural rewrite + a policy-head NNUE.
  *
  * == Architecture ==
  *
  * Replace negamax with PUCT-guided tree expansion:
  *
  * {{{
  * loop for N simulations or until time budget:
  *   leaf = selectPuct(root)              // walk down by PUCT
  *   (value, policy) = nnue.policyValue(leaf.state)
  *   leaf.expand(policy)                  // create children, store priors
  *   backpropagate(leaf, value)
  * return root.bestChild.move             // most-visited child
  * }}}
  *
  * PUCT score per child:
  *   score(c) = Q(c) + c_puct * P(c) * sqrt(N(parent)) / (1 + N(c))
  * where Q is win rate, P is the policy prior, N is visit count.
  *
  * == Policy network ==
  *
  * Extend the (768 -> 128) x 2 -> 1 value net with a second head:
  *   l2_policy: 2*128 -> 4096   (from-square * to-square)
  *   loss = lambda_v * MSE(sigmoid(value), wdl_blend)
  *        + lambda_p * cross_entropy(policy, one_hot(best_move))
  *
  * Training data already has FEN + score + WDL + bestMove — the
  * .plain emitted by NnueDataGen has every field MCTS needs.
  *
  * Move-space encoding: 4096 = 64 from-squares × 64 to-squares is
  * the simplest and covers every legal move (promotion piece-type
  * is ambiguous but the move generator + MCTS expansion handles
  * promotion at the engine side; the policy prior just gives one
  * (from, to) probability per piece-to-square pair).
  *
  * Output format (binary):
  *   [feature_weights : i16 × 768 × 128]
  *   [feature_bias    : i16 × 128]
  *   [value_weights   : i16 × 2*128]
  *   [value_bias      : i16]
  *   [policy_weights  : i16 × 2*128 × 4096]
  *   [policy_bias     : i16 × 4096]
  *
  * == Effort split ==
  *
  *   1. Modify train_nnue.py to add the policy head + retrain
  *      with combined loss. ~ half a day.
  *   2. Extend NnueEvaluator to produce policy logits alongside
  *      the existing value. New trait
  *      [[PolicyValueEvaluator]]. ~ half a day.
  *   3. Implement MctsNode, MctsTree, PUCT selection,
  *      expand/backup. ~ 2-3 days.
  *   4. Time-budgeted MCTS search loop wired as a new Search
  *      impl. ~ half a day.
  *   5. Data scale-up: 103k positions won't train a useful
  *      policy net. Need 1-10M positions minimum. Re-run
  *      NnueDataGen for ~24-48 hours. ~ overnight.
  *   6. A/B vs the α-β search. ~ a couple of tournaments.
  *
  * Total realistic timeline: 1-2 weeks of focused work.
  *
  * == Why this is the next major lever ==
  *
  * Lc0 achieves 3300+ Elo with MCTS + policy on a smaller raw
  * eval than Stockfish's NNUE. The exploration efficiency of
  * PUCT (refining the most promising lines based on cumulative
  * value estimates) beats α-β at high time budgets. For
  * fixed-depth play α-β still wins; for time-controlled play
  * (which is the real-world use case) MCTS dominates.
  *
  * Expected Elo gain over current α-β: +500-1000 at saturation,
  * assuming the policy net is trained on a comparable corpus
  * size to what we have for the value net (i.e., scaled
  * data-gen). With our current 103k positions and tiny model,
  * MCTS would likely lose to α-β — the policy prior would be
  * too noisy to give better-than-uniform exploration.
  *
  * Prerequisite: task #92 (search-eval rows in NnueDataGen) +
  * a 1M+ position dataset.
  */
object MctsRoadmap

package chess.analysis

import zio.*

import chess.bot.engine.Search
import chess.codec.UciCodec
import chess.model.GameError
import chess.model.board.{GameState, GameStatus, Move}
import chess.model.piece.Color
import chess.notation.SanSerializer
import chess.opening.EcoBook

/** Engine-driven post-game analysis: rates each played move against the
  * engine's best, names the opening, and scores per-side accuracy.
  *
  * Search effort is **adaptive**, not a flat depth (see [[GameAnalyzer]] object
  * for the knobs):
  *
  *   1. *Dynamic depth via iterative deepening.* Each position is deepened one
  *      ply at a time until the engine's verdict stops moving — the eval is
  *      unchanged (within [[GameAnalyzer.StableEps]]) for [[GameAnalyzer.StableRounds]]
  *      iterations — or a depth cap is hit. Quiet positions converge shallow and
  *      cheap; sharp ones are searched deeper exactly because they keep changing.
  *      The shared transposition table makes each extra ply near-incremental.
  *   2. *Two-pass pivot deepening.* A cheap shallow scan rates the whole game,
  *      then the moves at the sharpest win-% swings (and their neighbours) are
  *      re-analysed at the full depth cap — spending the deep search only where
  *      the evaluation is actually pivotal (and where a shallow read is most
  *      likely wrong, e.g. a missed forced mate).
  *   3. *Volatility-weighted accuracy.* The per-side game accuracy weights each
  *      move by how decisive the moment was (local win-% volatility) and blends
  *      a weighted mean with a harmonic mean — the Lichess recipe.
  *
  * Takes the parsed game as `(move, resultingState)` history (oldest-first, as
  * `PgnParser` produces). The `depth` argument is the **deep cap** — the ceiling
  * iterative deepening may reach on a pivotal move; the scan uses the smaller
  * [[GameAnalyzer.ScanDepthCap]]. Everything is deterministic given `depth`
  * (depth-bounded, fixed stability rule, single-threaded TT), so results are
  * reproducible and safely cacheable by `(pgn, depth)`.
  */
final class GameAnalyzer(search: Search, eco: EcoBook):

  import GameAnalyzer.*

  def analyze(
      initial: GameState,
      history: List[(Move, GameState)],
      depth: Int
  ): IO[GameError, GameAnalysis] =
    for
      // history is chronological (oldest-first); deriveMoveLog wants newest-first.
      log    <- SanSerializer.deriveMoveLog(initial, history.reverse)
      sans    = log.map(_._2)
      opening = eco.identify(sans)
      scanCap = math.min(depth, ScanDepthCap)
      // Pass 1: shallow, dynamic-depth scan of every ply.
      scan <- ZIO.foreach(history.indices.toList) { i =>
                analyzePly(i, preState(initial, history, i), history(i)._1,
                           history(i)._2, sans(i), scanCap, opening.plyMatched)
              }
      // Pass 2: re-analyse the pivotal plies (+ neighbours) at the full cap.
      pivots = if depth > scanCap then pivotPlies(scan) else Set.empty[Int]
      moves <- ZIO.foreach(scan.zipWithIndex) { case (m, i) =>
                 if pivots.contains(i) then
                   analyzePly(i, preState(initial, history, i), history(i)._1,
                              history(i)._2, sans(i), depth, opening.plyMatched)
                 else ZIO.succeed(m)
               }
      // Volatility-weighted per-side accuracy from the final (deepened) evals.
      weights = MoveQuality.volatilityWeights(50.0 :: moves.map(_.winPct))
    yield
      val tagged = moves.zip(weights)
      GameAnalysis(
        opening,
        moves,
        MoveQuality.weightedAccuracy(forColor(tagged, "white")),
        MoveQuality.weightedAccuracy(forColor(tagged, "black"))
      )

  private def forColor(
      tagged: List[(MoveAnalysis, Double)],
      color: String
  ): List[(Double, Double)] =
    tagged.collect { case (m, w) if m.color == color => (m.accuracy, w) }

  /** Plies whose move caused a sharp white-relative win-% swing (a blunder OR a
    * decisive winning blow — both deserve a deeper look), plus their immediate
    * neighbours so the comparison around the pivot is at the same depth. */
  private def pivotPlies(scan: List[MoveAnalysis]): Set[Int] =
    val winSeq = (50.0 :: scan.map(_.winPct)).toVector
    val sharp = scan.indices.filter { i =>
      math.abs(winSeq(i + 1) - winSeq(i)) >= SwingThreshold
    }
    sharp.flatMap(i => Seq(i - 1, i, i + 1)).filter(i => i >= 0 && i < scan.length).toSet

  private def preState(
      initial: GameState,
      history: List[(Move, GameState)],
      i: Int
  ): GameState =
    if i == 0 then initial else history(i - 1)._2

  private def analyzePly(
      i: Int,
      pre: GameState,
      played: Move,
      post: GameState,
      san: String,
      maxDepth: Int,
      plyMatched: Int
  ): IO[GameError, MoveAnalysis] =
    for
      // Iterative deepening on the pre-move position until the eval stabilises
      // (or the cap), returning the engine's best move, its score, the gap to
      // the 2nd-best (for "only move"), and the depth actually reached.
      stab <- stabilize(pre, maxDepth)
      (bestMv, bestCp, gap, reached) = stab
      // A move that ENDS the game can't be re-rooted by `evaluate` (the post
      // position has no legal moves → root returns 0, scoring a mate as a
      // catastrophic drop). Take the value from the rules verdict: mate is the
      // best outcome for the mover, a stalemate/draw is a dead-even 0. (The bot
      // never hits this — its negamax scores terminal nodes inside the search.)
      playedCp <- post.status match
                    case GameStatus.Checkmate(_)   => ZIO.succeed(Search.MateScore)
                    case s if !s.isPlaying          => ZIO.succeed(0)
                    case _ => search.evaluate(post, reached).map(c => -c)
      cpLoss  = math.max(0, bestCp - playedCp)
      drop    = math.max(0.0, WinProb.pct(bestCp) - WinProb.pct(playedCp))
      cls     = MoveQuality.classify(drop, played == bestMv, gap, i < plyMatched)
      bestSan <- SanSerializer.toSan(bestMv, pre)
      pv      <- search.principalVariation(pre, reached)
      white    = pre.activeColor == Color.White
    yield MoveAnalysis(
      ply = i,
      color = if white then "white" else "black",
      san = san,
      evalCp = if white then playedCp else -playedCp,
      winPct = if white then WinProb.pct(playedCp) else 100.0 - WinProb.pct(playedCp),
      cpLoss = cpLoss,
      accuracy = WinProb.accuracy(drop),
      moveClass = cls,
      bestMove = bestSan,
      pv = pv.map(UciCodec.serialize)
    )

  /** Iterative-deepening multi-PV on `pre` with an eval-stability early stop.
    * Returns `(bestMove, bestCp, gapToSecondCp, reachedDepth)` from the deepest
    * iteration run. Stops once the best score has held within [[StableEps]] for
    * [[StableRounds]] consecutive deepenings (past [[MinStableDepth]]), or at
    * `maxDepth`. `pre` always has a legal move (a move was played from it), so
    * `bestMoves` is non-empty. */
  private def stabilize(pre: GameState, maxDepth: Int): UIO[(Move, Int, Int, Int)] =
    def loop(d: Int, prevCp: Int, stable: Int): UIO[(Move, Int, Int, Int)] =
      search.bestMoves(pre, d, 2).flatMap { scored =>
        val (bestMv, bestCp) = scored.head
        val gap     = bestCp - scored.lift(1).map(_._2).getOrElse(bestCp)
        val nextStable =
          if d > 1 && math.abs(bestCp - prevCp) <= StableEps then stable + 1 else 0
        val converged = d >= MinStableDepth && nextStable >= StableRounds
        if d >= maxDepth || converged then ZIO.succeed((bestMv, bestCp, gap, d))
        else loop(d + 1, bestCp, nextStable)
      }
    loop(1, 0, 0)

object GameAnalyzer:
  /** Cap for the whole-game shallow scan (pass 1). */
  private val ScanDepthCap = 4
  /** Win-% points a single move must swing to be re-searched at the deep cap. */
  private val SwingThreshold = 8.0
  /** Eval (cp) wobble treated as "unchanged" for the stability stop. */
  private val StableEps = 15
  /** Consecutive stable iterations required to stop deepening early. */
  private val StableRounds = 2
  /** Never stop before this depth (depth-1/2 evals are too noisy to trust). */
  private val MinStableDepth = 3

package chess.analysis

import zio.*

import chess.bot.engine.Search
import chess.codec.UciCodec
import chess.model.GameError
import chess.model.board.{GameState, GameStatus, Move}
import chess.model.piece.Color
import chess.notation.SanSerializer
import chess.opening.EcoBook

/** Engine-driven post-game analysis: rates each played move by comparing it to
  * the engine's best, names the opening, and computes per-side accuracy.
  *
  * Takes the parsed game as `(move, resultingState)` history (e.g. straight from
  * `PgnParser`), so a stored archive analyses via `PgnParser.parse(archive.pgn)`.
  * For each ply it searches the pre-move position once (`bestMoves` → best score
  * + gap to the 2nd move) and evaluates the resulting position (`-evaluate` =
  * the move's value to the mover), converts to win-% and classifies the drop.
  */
final class GameAnalyzer(search: Search, eco: EcoBook):

  def analyze(
      initial: GameState,
      history: List[(Move, GameState)],
      depth: Int
  ): IO[GameError, GameAnalysis] =
    for
      // history is chronological (oldest-first, as PgnParser produces);
      // deriveMoveLog expects newest-first, so feed it reversed.
      log    <- SanSerializer.deriveMoveLog(initial, history.reverse)
      sans    = log.map(_._2)
      opening = eco.identify(sans)
      moves <- ZIO.foreach(history.indices.toList) { i =>
                 analyzePly(
                   i,
                   preState(initial, history, i),
                   history(i)._1,
                   history(i)._2,
                   sans(i),
                   depth,
                   opening.plyMatched
                 )
               }
    yield GameAnalysis(
      opening,
      moves,
      MoveQuality.averageAccuracy(moves.filter(_.color == "white").map(_.accuracy)),
      MoveQuality.averageAccuracy(moves.filter(_.color == "black").map(_.accuracy))
    )

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
      depth: Int,
      plyMatched: Int
  ): IO[GameError, MoveAnalysis] =
    for
      scored          <- search.bestMoves(pre, depth, 2)
      (bestMv, bestCp) = scored.head
      gap              = bestCp - scored.lift(1).map(_._2).getOrElse(bestCp)
      // A move that ENDS the game can't be re-rooted by `evaluate`: the post
      // position has no legal moves, so the engine's root search returns 0,
      // which scores a checkmate as a catastrophic drop (the "mate = blunder"
      // bug). Take the played move's value from the rules verdict instead —
      // delivering mate is the best possible outcome for the mover, a
      // stalemate/draw is dead-even 0. (The bot never hits this: its negamax
      // scores terminal nodes via `terminalScore` *inside* the search, so it
      // picks mating moves normally.)
      playedCp        <- post.status match
                           case GameStatus.Checkmate(_) =>
                             ZIO.succeed(Search.MateScore)
                           case s if !s.isPlaying =>
                             ZIO.succeed(0)
                           case _ =>
                             search.evaluate(post, depth).map(c => -c)
      cpLoss            = math.max(0, bestCp - playedCp)
      drop              = math.max(0.0, WinProb.pct(bestCp) - WinProb.pct(playedCp))
      cls               = MoveQuality.classify(drop, played == bestMv, gap, i < plyMatched)
      bestSan          <- SanSerializer.toSan(bestMv, pre)
      pv               <- search.principalVariation(pre, depth)
      white             = pre.activeColor == Color.White
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

package chess.bot.train

import java.io.BufferedWriter
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import zio.*

import chess.bot.engine.{ArrayTaperedEvaluator, OpeningBook, Search, WeightsLoader}
import chess.codec.FenSerializer
import chess.model.board.GameState
import chess.model.piece.Color

/** Self-play data generation for NNUE training.
  *
  * For every game played, walks the move history and writes one
  * row per *quiet* position to `nnue-data.plain`:
  *
  * {{{
  *   fen | score | result | best
  * }}}
  *
  *   * `fen`    — full FEN of the position
  *   * `score`  — centipawn eval from white's POV, taken from the
  *                search that picked the next move
  *   * `result` — game outcome from white's POV: `1.0` win, `0.5`
  *                draw, `0.0` loss
  *   * `best`   — the move actually played (UCI), so Bullet can
  *                weight the loss accordingly
  *
  * This is the canonical Bullet trainer input format; see the
  * Bullet wiki for the binpack converter that turns `.plain` into
  * the chain-compressed binpack the trainer prefers.
  *
  * Quietness filter: positions where either side is in check, or
  * where the chosen move was a capture, are skipped — those are
  * tactical positions where a static eval target would be noisy
  * (the score from a TT entry one capture away from the position
  * itself isn't a reliable target).
  *
  * Run via:
  * {{{
  *   sbt 'botTrain/runMain chess.bot.train.NnueDataGen'
  * }}}
  *
  * Configurable via env vars:
  *   - `PICHESS_NNUE_GAMES`         games to play (default 1000)
  *   - `PICHESS_NNUE_DEPTH`         per-move search depth (default 8)
  *   - `PICHESS_NNUE_MAX_PLIES`     cap to terminate shufflers (200)
  *   - `PICHESS_NNUE_OUT`           output path (default
  *                                  `/tmp/chess-corpus/nnue-data.plain`)
  *   - `PICHESS_NNUE_VERSION`       weights snapshot to play with (8)
  *   - `PICHESS_NNUE_PARALLELISM`   games run concurrently (4)
  *
  * A 1000-game run at depth 8 typically yields ~50-80k quiet
  * positions on this hardware (~30-50 quiets per game; 200-ply
  * cap, ~50 % games hit the cap). Bullet wants 50-100 M positions
  * for a "real" net, so scale up via repeated runs or by raising
  * `PICHESS_NNUE_GAMES`.
  */
object NnueDataGen extends ZIOAppDefault:

  private val defaultOut = "/tmp/chess-corpus/nnue-data.plain"

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] = program

  private def program: ZIO[Scope, Throwable, Unit] =
    for
      cfg     <- readConfig
      _       <- ZIO.logInfo(s"NnueDataGen config: $cfg")
      // Search itself runs single-thread — the game-level
      // `ZIO.foreachPar` saturates the CPU with one fiber per
      // game, so any YBWC fan-out inside each search would just
      // fight other games for cores.
      search  <- buildSearch(cfg.weightsVersion, searchParallelism = 1)
      sfOpp   <- cfg.sfOpponentSkill match
                   case Some(skill) =>
                     StockfishSearch
                       .spawn(skillLevel = Some(skill), label = s"sf-skill$skill")
                       .map(Some(_))
                   case None => ZIO.succeed(None)
      // Round-robin through the opening pool so the dataset
      // covers a variety of pawn structures, not 1000 games of
      // the same line.
      openings <- ZIO.foreach(OpeningPool.fens) { fen =>
                    chess.codec.FenParserRegex.parse(fen)
                  }
      // Open the output file once and hand the writer to every
      // game-finish callback so we don't pay the open/close cost
      // per game.
      _       <- ZIO.scoped {
                   openWriter(Paths.get(cfg.outputPath)).flatMap { writer =>
                     playAll(search, sfOpp, openings, cfg, writer)
                   }
                 }
      _       <- ZIO.logInfo(s"Wrote ${cfg.outputPath}")
    yield ()

  private final case class Config(
      games:           Int,
      depth:           Int,
      scoringDepth:    Int,
      multiPvK:        Int,
      scoreClipCp:     Int,
      maxPlies:        Int,
      noisePlies:      Int,
      outputPath:      String,
      weightsVersion:  Int,
      parallelism:     Int,
      sfOpponentSkill: Option[Int],
  )

  private def readConfig: UIO[Config] = ZIO.succeed {
    def intEnv(name: String, default: Int): Int =
      sys.env.get(name).flatMap(_.toIntOption).getOrElse(default)
    Config(
      games          = intEnv("PICHESS_NNUE_GAMES", 1000),
      depth          = intEnv("PICHESS_NNUE_DEPTH", 8),
      // Per-row scoring depth. Lower than gameplay depth so the
      // labelling pass doesn't dominate runtime (~50 ms/row at
      // depth 4 vs ~hundreds of ms at depth 6+).
      scoringDepth   = intEnv("PICHESS_NNUE_SCORING_DEPTH", 4),
      // Multi-PV K — top-K moves with their scores per row. K=3
      // is the standard policy-net training shape; K=1 reduces
      // back to single-best (cheaper labelling).
      multiPvK       = intEnv("PICHESS_NNUE_MULTIPV", 3),
      // Score clip in centipawns. Mate scores (~99936+) and very
      // sharp positions otherwise dominate the loss; clipping to
      // ±2000 cp keeps the regression target inside a reasonable
      // sigmoid range.
      scoreClipCp    = intEnv("PICHESS_NNUE_SCORE_CLIP", 2000),
      maxPlies       = intEnv("PICHESS_NNUE_MAX_PLIES", 200),
      // Random-noise plies: apply N random legal moves to the
      // opening FEN before play starts. Diversifies the
      // distribution of post-opening positions seen — without it
      // each opening produces the same self-play line every time.
      noisePlies     = intEnv("PICHESS_NNUE_NOISE_PLIES", 2),
      outputPath     = sys.env.getOrElse("PICHESS_NNUE_OUT", defaultOut),
      weightsVersion = intEnv("PICHESS_NNUE_VERSION", 8),
      parallelism    = intEnv("PICHESS_NNUE_PARALLELISM", 4),
      // Optional Stockfish opponent skill level (0-20). When set,
      // odd-indexed games play against Stockfish instead of self-
      // play, diversifying training data with non-pichess style.
      // Unset → pure self-play (current behavior).
      sfOpponentSkill = sys.env.get("PICHESS_NNUE_SF_SKILL").flatMap(_.toIntOption),
    )
  }

  private def buildSearch(version: Int, searchParallelism: Int): ZIO[Any, Throwable, Search] =
    WeightsLoader.load(version).map { snapshot =>
      Search.alphaBeta(
        eval        = ArrayTaperedEvaluator(snapshot.weights),
        book        = OpeningBook.Empty,
        parallelism = searchParallelism,
      )
    }

  private def openWriter(path: Path): ZIO[Scope, Throwable, BufferedWriter] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        Files.createDirectories(path.getParent)
        Files.newBufferedWriter(
          path,
          java.nio.charset.StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
        )
      }
    )(w => ZIO.attemptBlocking(w.close()).orDie)

  private def playAll(
      search: Search,
      sfOpp: Option[Search],
      openings: Vector[GameState],
      cfg: Config,
      writer: BufferedWriter,
  ): UIO[Unit] =
    val counter = java.util.concurrent.atomic.AtomicInteger(0)
    // Per-fiber RNG seeded by game index for reproducibility +
    // diversity. The same FEN seed across runs reproduces the same
    // noise; different game indices in the same run get different
    // noise.
    ZIO
      .foreachPar(0 until cfg.games) { i =>
        val openingBase = openings((i / 2) % openings.size)
        val rng = new scala.util.Random(0xC0FFEEL ^ i.toLong)
        val openingStart =
          if cfg.noisePlies > 0 then applyNoise(openingBase, cfg.noisePlies, rng)
          else openingBase
        // Pair each game with either the self-play opponent or the
        // Stockfish opponent on odd-indexed games. The single SF
        // subprocess serializes via its lock — half the games
        // become serial, the other half stay parallel.
        val opponent = sfOpp match
          case Some(sf) if i % 2 == 1 => sf
          case _                       => search
        SelfPlay
          .playGame(search, opponent, cfg.depth, cfg.maxPlies, openingStart)
          .flatMap { result =>
            writeGame(writer, search, cfg.scoringDepth, cfg.multiPvK,
                      cfg.scoreClipCp, openingStart, result)
          }
          .tap { _ =>
            val done = counter.incrementAndGet()
            if done % 50 == 0 then ZIO.logInfo(s"played $done / ${cfg.games}")
            else ZIO.unit
          }
      }
      .withParallelism(cfg.parallelism)
      .unit

  /** Apply `n` random legal moves to `state` for opening
    * diversification. The chosen move per ply is uniformly random
    * over the legal-move list (no eval bias) so we explore wider
    * positions than self-play tends to. Returns the diversified
    * start state. Falls back to `state` if a noise step produces
    * no legal moves (we hit a terminal sequence). */
  private def applyNoise(state: GameState, n: Int, rng: scala.util.Random): GameState =
    if n <= 0 then state
    else
      // Enumerate legal moves via the public MoveValidator
      // destinations index (sync, no ZIO), pick one uniformly at
      // random, apply via the synchronous core. RulesAdapter is
      // `private[engine]` so we can't reach it from bot-train.
      val destinations =
        chess.model.rules.MoveValidator.legalDestinationsIndexSync(state)
      val moves = destinations.toVector.flatMap { case (from, tos) =>
        tos.map(to => chess.model.board.Move(from, to, promotion = None))
      }
      if moves.isEmpty then state
      else
        val choice = moves(rng.nextInt(moves.size))
        chess.model.rules.Game.applyMoveCoreSync(state, choice) match
          case Some(next) => applyNoise(next, n - 1, rng)
          case None       => state

  /** Walk the played-out game; for every quiet position, score it
    * with a fresh search and emit a Bullet `.plain` row.
    *
    * Row format (extended): `fen | score | wdl | m1:s1,m2:s2,...`
    * where the comma-separated trailing column is the top-K moves
    * with their white-POV centipawn scores, ordered best-first.
    * The "score" column equals the first move's score (so Bullet
    * still parses cleanly — its text loader ignores the 4th
    * column). The multi-PV trail enables future policy-net
    * training without re-generating the data.
    *
    * Score clipping: every score is clamped to `±scoreClipCp`
    * before write. Mate ladder scores (~99936+) and very sharp
    * positions otherwise dominate the regression target; ±2000 cp
    * keeps everything inside a useful sigmoid range. */
  private def writeGame(
      writer: BufferedWriter,
      search: Search,
      scoringDepth: Int,
      multiPvK: Int,
      scoreClipCp: Int,
      initial: GameState,
      result: SelfPlay.GameResult,
  ): UIO[Unit] =
    val wdlWhite = result.outcome match
      case SelfPlay.Outcome.WhiteWins       => 1.0
      case SelfPlay.Outcome.BlackWins       => 0.0
      case SelfPlay.Outcome.Draw            => 0.5
      case SelfPlay.Outcome.MaxMovesReached => 0.5
    // Walk pre-states first to collect every quiet position; then
    // score them all in one ZIO traversal so the per-row blocking
    // I/O happens once at the end, after every search has run.
    val builder = scala.collection.mutable.ArrayBuffer.empty[GameState]
    var pre = initial
    result.history.foreach { case (move, post) =>
      val isCapture = pre.board.contains(move.to) || isEnPassant(pre, move)
      val isCheck   = pre.inCheck || post.inCheck
      if !isCapture && !isCheck then builder += pre
      pre = post
    }
    val quietRows = builder.toVector

    inline def toWhitePov(stmScore: Int, state: GameState): Int =
      val raw =
        if state.activeColor == chess.model.piece.Color.White then stmScore
        else -stmScore
      math.max(-scoreClipCp, math.min(scoreClipCp, raw))

    // For each pre-state, get top-K (move, score) pairs at
    // `scoringDepth`. The returned scores are STM-POV; we flip +
    // clip to white-POV here so the writer side just formats text.
    ZIO
      .foreach(quietRows) { preState =>
        search.bestMoves(preState, scoringDepth, multiPvK).map { topK =>
          val whitePov = topK.map { case (m, s) => (m, toWhitePov(s, preState)) }
          FenSerializer.serialize(preState) -> whitePov
        }
      }
      .flatMap { scored =>
        ZIO.attemptBlocking {
          writer.synchronized {
            scored.foreach { case (fen, topK) =>
              if topK.nonEmpty then
                val headScore = topK.head._2
                val mpvStr = topK
                  .map { case (m, s) => s"${toUci(m)}:$s" }
                  .mkString(",")
                writer.write(fen); writer.write(" | ")
                writer.write(headScore.toString); writer.write(" | ")
                writer.write(formatDouble(wdlWhite)); writer.write(" | ")
                writer.write(mpvStr)
                writer.newLine()
            }
            writer.flush()
          }
        }.orDie
      }

  private def isEnPassant(state: GameState, move: chess.model.board.Move): Boolean =
    state.enPassantTarget.contains(move.to) && {
      state.board.get(move.from).exists(_.pieceType == chess.model.piece.PieceType.Pawn)
    }

  private def toUci(m: chess.model.board.Move): String =
    val from = s"${m.from.col}${m.from.row}"
    val to   = s"${m.to.col}${m.to.row}"
    val promo = m.promotion match
      case Some(chess.model.piece.PieceType.Queen)  => "q"
      case Some(chess.model.piece.PieceType.Rook)   => "r"
      case Some(chess.model.piece.PieceType.Bishop) => "b"
      case Some(chess.model.piece.PieceType.Knight) => "n"
      case _                                         => ""
    s"$from$to$promo"

  private def formatDouble(d: Double): String = d match
    case 1.0 => "1.0"
    case 0.5 => "0.5"
    case _   => "0.0"

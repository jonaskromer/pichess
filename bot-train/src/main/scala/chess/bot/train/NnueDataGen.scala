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
                     playAll(search, openings, cfg, writer)
                   }
                 }
      _       <- ZIO.logInfo(s"Wrote ${cfg.outputPath}")
    yield ()

  private final case class Config(
      games:           Int,
      depth:           Int,
      scoringDepth:    Int,
      maxPlies:        Int,
      outputPath:      String,
      weightsVersion:  Int,
      parallelism:     Int,
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
      maxPlies       = intEnv("PICHESS_NNUE_MAX_PLIES", 200),
      outputPath     = sys.env.getOrElse("PICHESS_NNUE_OUT", defaultOut),
      weightsVersion = intEnv("PICHESS_NNUE_VERSION", 8),
      parallelism    = intEnv("PICHESS_NNUE_PARALLELISM", 4),
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
      openings: Vector[GameState],
      cfg: Config,
      writer: BufferedWriter,
  ): UIO[Unit] =
    val counter = java.util.concurrent.atomic.AtomicInteger(0)
    ZIO
      .foreachPar(0 until cfg.games) { i =>
        val opening = openings((i / 2) % openings.size)
        SelfPlay
          .playGame(search, search, cfg.depth, cfg.maxPlies, opening)
          .flatMap { result =>
            writeGame(writer, search, cfg.scoringDepth, opening, result)
          }
          .tap { _ =>
            val done = counter.incrementAndGet()
            if done % 50 == 0 then ZIO.logInfo(s"played $done / ${cfg.games}")
            else ZIO.unit
          }
      }
      .withParallelism(cfg.parallelism)
      .unit

  /** Walk the played-out game; for every quiet position, score it
    * with a fresh search and emit a Bullet `.plain` row.
    *
    * The score per row comes from `Search.evaluate(pre, depth)` —
    * one shallow search per emitted position. Cost is roughly
    * `quietRows × searchTime`; for ~30 quiets/game at depth 4
    * (~50ms each) that's ~1.5s of extra work per game. The
    * resulting training target is dramatically better than the
    * material-only placeholder: it captures piece coordination,
    * king safety, tempo, and the rest of the eval's signal.
    *
    * `scoringDepth` is usually lower than the gameplay depth —
    * we want each row labelled fast, not deeply analysed. Depth
    * 4 gives reasonable eval at ~50ms; depth 6 doubles the cost
    * for a slightly stronger label. */
  private def writeGame(
      writer: BufferedWriter,
      search: Search,
      scoringDepth: Int,
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
    val builder = scala.collection.mutable.ArrayBuffer.empty[(GameState, chess.model.board.Move)]
    var pre = initial
    result.history.foreach { case (move, post) =>
      val isCapture = pre.board.contains(move.to) || isEnPassant(pre, move)
      val isCheck   = pre.inCheck || post.inCheck
      if !isCapture && !isCheck then builder += ((pre, move))
      pre = post
    }
    val quietRows = builder.toVector
    // Search each pre-state at `scoringDepth`; the returned score
    // is from the side-to-move's POV, so we flip on black to get
    // a white-relative score (Bullet expects white-relative).
    ZIO
      .foreach(quietRows) { case (preState, move) =>
        search.evaluate(preState, scoringDepth).map { stmScore =>
          val whiteScore =
            if preState.activeColor == chess.model.piece.Color.White then stmScore
            else -stmScore
          (FenSerializer.serialize(preState), whiteScore, move)
        }
      }
      .flatMap { scored =>
        ZIO.attemptBlocking {
          writer.synchronized {
            scored.foreach { case (fen, whiteScore, move) =>
              writer.write(fen); writer.write(" | ")
              writer.write(whiteScore.toString); writer.write(" | ")
              writer.write(formatDouble(wdlWhite)); writer.write(" | ")
              writer.write(toUci(move))
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

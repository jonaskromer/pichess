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
      built   <- buildSearch(cfg.weightsVersion, searchParallelism = 1)
      search  = built._1
      rawEval = built._2
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
                     playAll(search, rawEval, sfOpp, openings, cfg, writer)
                   }
                 }
      _       <- ZIO.logInfo(s"Wrote ${cfg.outputPath}")
    yield ()

  private final case class Config(
      games:           Int,
      depth:           Int,
      scoringDepth:    Int,
      multiPvK:        Int,
      pvLength:        Int,
      multiDepthSpan:  Int,
      tacticalThresh:  Int,
      engineTag:       String,
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
      // PV-continuation length: extract up to N plies of the best
      // line from the TT after the scoring search. The head move
      // is the first entry; the continuation is what the engine
      // expects each side to play after that. Used for MCTS
      // policy-net / planning-net training. 0 = no continuation.
      pvLength       = intEnv("PICHESS_NNUE_PV_LENGTH", 6),
      // Multi-depth span: emit scores at depths
      // `scoringDepth-N..scoringDepth+N` so the trainer has a
      // depth-stability signal. 1 means 3 scores (d-1, d, d+1).
      // Each extra depth costs one more search per row.
      multiDepthSpan = intEnv("PICHESS_NNUE_MULTI_DEPTH_SPAN", 1),
      // Tactical-alarm threshold in centipawns: if any pair of
      // adjacent multi-depth scores differs by more than this
      // amount, the position is flagged tactical (bit `1`).
      tacticalThresh = intEnv("PICHESS_NNUE_TACTICAL_CP", 100),
      // Engine identity tag emitted in the `eng` column. Default
      // names the weights snapshot version (e.g., `pichess-v8`);
      // override to mark Stockfish games when running with
      // PICHESS_NNUE_SF_SKILL.
      engineTag      = sys.env.getOrElse(
                         "PICHESS_NNUE_ENGINE_TAG",
                         s"pichess-v${intEnv("PICHESS_NNUE_VERSION", 8)}",
                       ),
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

  /** Builds the search AND exposes the underlying evaluator
    * separately. The evaluator is used directly for
    * `evaluateComponents` (the search wraps it but doesn't expose
    * it back through the Search trait). */
  private def buildSearch(
      version: Int,
      searchParallelism: Int,
  ): ZIO[Any, Throwable, (Search, chess.bot.engine.Evaluator)] =
    WeightsLoader.load(version).map { snapshot =>
      val eval = ArrayTaperedEvaluator(snapshot.weights)
      val search = Search.alphaBeta(
        eval        = eval,
        book        = OpeningBook.Empty,
        parallelism = searchParallelism,
      )
      (search, eval)
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
      rawEval: chess.bot.engine.Evaluator,
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
        val isSfGame = sfOpp.isDefined && i % 2 == 1
        val opponent = sfOpp match
          case Some(sf) if isSfGame => sf
          case _                    => search
        // The engine that *played* this row's quiet positions is
        // the challenger (self), regardless of opponent identity.
        // We mark the row with the opponent identity so training
        // can stratify or weight by opponent later.
        val gameTag =
          if isSfGame then s"${cfg.engineTag}-vs-sf${cfg.sfOpponentSkill.getOrElse(0)}"
          else cfg.engineTag
        SelfPlay
          .playGame(search, opponent, cfg.depth, cfg.maxPlies, openingStart)
          .flatMap { result =>
            writeGame(
              writer, search, rawEval, cfg.scoringDepth, cfg.multiPvK,
              cfg.pvLength, cfg.multiDepthSpan, cfg.tacticalThresh,
              cfg.scoreClipCp, gameTag, openingStart, result,
            )
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
    * with a multi-depth search and emit an extended 9-column row.
    *
    * Row format (pipe-separated):
    * {{{
    *   fen | score | wdl | mpv | mds | comps | tms | tact | eng
    * }}}
    *
    *   * `fen`   — full FEN of the pre-move state
    *   * `score` — head-move's white-POV centipawn score (clipped).
    *               Kept as column 2 so Bullet's text parser (which
    *               only reads first 3 columns) is unchanged.
    *   * `wdl`   — game outcome from white POV (1.0 / 0.5 / 0.0)
    *   * `mpv`   — multi-PV with PV continuation on head:
    *               `m1:s1/cont:c1c2c3...,m2:s2,m3:s3`
    *   * `mds`   — multi-depth scores spanning `±multiDepthSpan`:
    *               `d3:s3,d4:s4,d5:s5`
    *   * `comps` — eval-component breakdown:
    *               `mat:X,pst:Y,mob:Z,ps:W,ks:V,rook:U,misc:T`
    *               (sum = the eval's total)
    *   * `tms`   — wall-clock milliseconds for this row's deepest
    *               scoring search + multi-PV + PV walk
    *   * `tact`  — tactical-alarm bit (`1` if any adjacent pair
    *               of multi-depth scores differs by more than
    *               `tacticalThresh` cp)
    *   * `eng`   — engine identity tag for this row
    *
    * Cost: `(2 × multiDepthSpan + 1)` searches per row plus a
    * cheap multi-PV + PV walk. Default span=1 gives 3 searches
    * per quiet row vs the prior 1, ~3× the labelling time.
    */
  private def writeGame(
      writer: BufferedWriter,
      search: Search,
      rawEval: chess.bot.engine.Evaluator,
      scoringDepth: Int,
      multiPvK: Int,
      pvLength: Int,
      multiDepthSpan: Int,
      tacticalThresh: Int,
      scoreClipCp: Int,
      engineTag: String,
      initial: GameState,
      result: SelfPlay.GameResult,
  ): UIO[Unit] =
    val wdlWhite = result.outcome match
      case SelfPlay.Outcome.WhiteWins       => 1.0
      case SelfPlay.Outcome.BlackWins       => 0.0
      case SelfPlay.Outcome.Draw            => 0.5
      case SelfPlay.Outcome.MaxMovesReached => 0.5
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

    val depths =
      ((scoringDepth - multiDepthSpan).max(1) to (scoringDepth + multiDepthSpan)).toVector

    ZIO
      .foreach(quietRows) { preState =>
        for
          // Multi-depth scores. Cheapest to deepest so the TT is
          // warm by the time the deepest search runs.
          mds <- ZIO.foreach(depths) { d =>
                   search.evaluate(preState, d).map(s => d -> s)
                 }
          startNs = java.lang.System.nanoTime()
          // Deepest search for multi-PV + PV walk. Reuses TT entries
          // from the multi-depth pass so it's essentially free.
          topK <- search.bestMoves(preState, scoringDepth, multiPvK)
          pv   <- search.principalVariation(preState, scoringDepth, pvLength)
          tms = (java.lang.System.nanoTime() - startNs) / 1_000_000L
        yield
          val whiteTopK = topK.map { case (m, s) => (m, toWhitePov(s, preState)) }
          val whiteMds  = mds.map { case (d, s) => (d, toWhitePov(s, preState)) }
          val tact = isTactical(whiteMds.map(_._2), tacticalThresh)
          val comps = rawEval.evaluateComponents(preState)
          RowData(
            fen   = FenSerializer.serialize(preState),
            topK  = whiteTopK,
            pv    = pv,
            mds   = whiteMds,
            comps = comps,
            tms   = tms,
            tact  = tact,
            eng   = engineTag,
          )
      }
      .flatMap { rows =>
        ZIO.attemptBlocking {
          writer.synchronized {
            rows.foreach(writeRow(writer, wdlWhite, _))
            writer.flush()
          }
        }.orDie
      }

  private final case class RowData(
      fen: String,
      topK: List[(chess.model.board.Move, Int)],
      pv: List[chess.model.board.Move],
      mds: Vector[(Int, Int)],
      comps: Map[String, Int],
      tms: Long,
      tact: Int,
      eng: String,
  )

  private def writeRow(
      writer: BufferedWriter,
      wdlWhite: Double,
      row: RowData,
  ): Unit =
    if row.topK.isEmpty then ()
    else
      val headScore = row.topK.head._2
      val pvCont = row.pv.drop(1) // continuation = PV beyond the head move
      val mpvStr =
        val parts = row.topK.zipWithIndex.map { case ((m, s), idx) =>
          val cont =
            if idx == 0 && pvCont.nonEmpty then
              "/cont:" + pvCont.map(toUci).mkString("")
            else ""
          s"${toUci(m)}:$s$cont"
        }
        parts.mkString(",")
      val mdsStr   = row.mds.map { case (d, s) => s"d$d:$s" }.mkString(",")
      val compOrder = Seq("mat", "pst", "mob", "ps", "ks", "rook", "misc", "total")
      val compsStr  = compOrder
        .flatMap(k => row.comps.get(k).map(v => s"$k:$v"))
        .mkString(",")
      writer.write(row.fen);            writer.write(" | ")
      writer.write(headScore.toString); writer.write(" | ")
      writer.write(formatDouble(wdlWhite)); writer.write(" | ")
      writer.write(mpvStr);              writer.write(" | ")
      writer.write(mdsStr);              writer.write(" | ")
      writer.write(compsStr);            writer.write(" | ")
      writer.write(row.tms.toString);    writer.write(" | ")
      writer.write(row.tact.toString);   writer.write(" | ")
      writer.write(row.eng)
      writer.newLine()

  /** Tactical alarm: any adjacent pair of multi-depth scores
    * differing by more than `threshold` cp signals a position
    * where the eval is volatile to search depth. */
  private def isTactical(scores: Vector[Int], threshold: Int): Int =
    if scores.size < 2 then 0
    else
      val flipped = scores.sliding(2).exists { pair =>
        math.abs(pair(0) - pair(1)) > threshold
      }
      if flipped then 1 else 0

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

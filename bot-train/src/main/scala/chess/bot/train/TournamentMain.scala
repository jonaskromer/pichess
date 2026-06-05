package chess.bot.train

import zio.*

import chess.bot.engine.{ArrayTaperedEvaluator, Evaluator, OpeningBook, Search, WeightsLoader}

/** Standalone CLI for running a head-to-head tournament between
  * two weight snapshots (or vs a Stockfish baseline) and reporting
  * an Elo delta.
  *
  * Run via:
  * {{{
  *   sbt 'botTrain/runMain chess.bot.train.TournamentMain'
  * }}}
  *
  * Configurable via environment variables:
  *   - `PICHESS_TOURNAMENT_CHALLENGER`   weights version under test (default: 3)
  *   - `PICHESS_TOURNAMENT_CHAMPION`     baseline weights version (default: 2)
  *   - `PICHESS_TOURNAMENT_GAMES`        N games (default: 30)
  *   - `PICHESS_TOURNAMENT_DEPTH`        per-move search depth (default: 3)
  *   - `PICHESS_TOURNAMENT_PARALLELISM`  game-level parallelism (default: 4)
  *   - `PICHESS_TOURNAMENT_VS_STOCKFISH` `true` → opponent is Stockfish
  *                                       (default: false; opponent is the
  *                                        version specified by `_CHAMPION`)
  *   - `PICHESS_TOURNAMENT_STOCKFISH_SKILL`  Stockfish UCI Skill Level (0-20,
  *                                          default: 5 — roughly amateur)
  *
  * Use cases:
  *   - After [[TrainMain]] writes `weights/v3.json`, run with
  *     defaults to see whether v3 actually beats v2.
  *   - Set `_VS_STOCKFISH=true _STOCKFISH_SKILL=0` for a "can we
  *     beat the weakest Stockfish?" floor measurement; bump
  *     skill upward until the bot loses 50/50 → approximate
  *     absolute Elo rating. */
object TournamentMain extends ZIOAppDefault:

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    program

  private def program: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for
        cfg <- readConfig
        _   <- ZIO.logInfo(matchupLabel(cfg))
        challenger <- loadSearch(cfg.challenger)
        champion   <- loadOpponent(cfg)
        // Resolve the opening pool. Empty pool → all games start
        // from startpos (90%+ draws between similar bots). Non-
        // empty pool → diversifies games for a decisive signal.
        openings <- ZIO.foreach(if cfg.useOpenings then OpeningPool.fens else Vector.empty)(
                      fen => chess.codec.FenParserRegex.parse(fen)
                    )
        report <- Tournament.play(
                    challenger = challenger,
                    champion   = champion,
                    games      = cfg.games,
                    depth      = cfg.depth,
                    parallelism = cfg.parallelism,
                    openingStates = openings,
                  )
        _ <- ZIO.logInfo(report.render)
      yield ()
    }

  private def matchupLabel(cfg: Config): String =
    val opponent =
      if cfg.vsStockfish then s"Stockfish (Skill Level ${cfg.stockfishSkill})"
      else s"v${cfg.champion}"
    val parNote =
      // Stockfish runs as a single subprocess — only ONE concurrent
      // bestMove call works. Force parallelism=1 in the Stockfish
      // path; ignore the user setting.
      if cfg.vsStockfish then "1 (forced — single Stockfish subprocess)"
      else cfg.parallelism.toString
    s"Tournament: v${cfg.challenger} (challenger) vs $opponent (champion), " +
      s"${cfg.games} games at depth ${cfg.depth}, parallelism $parNote"

  private final case class Config(
      challenger: Int,
      champion:   Int,
      games:      Int,
      depth:      Int,
      parallelism: Int,
      vsStockfish: Boolean,
      stockfishSkill: Int,
      useOpenings: Boolean,
  )

  private def readConfig: UIO[Config] =
    ZIO.succeed {
      val vsSf = sys.env.get("PICHESS_TOURNAMENT_VS_STOCKFISH")
        .exists(_.equalsIgnoreCase("true"))
      Config(
        challenger     = intEnv("PICHESS_TOURNAMENT_CHALLENGER", 3),
        champion       = intEnv("PICHESS_TOURNAMENT_CHAMPION",   2),
        games          = intEnv("PICHESS_TOURNAMENT_GAMES",      30),
        depth          = intEnv("PICHESS_TOURNAMENT_DEPTH",      3),
        // Force parallelism=1 when opponent is Stockfish — only
        // one subprocess, can't share across fibers concurrently.
        parallelism    = if vsSf then 1
                         else intEnv("PICHESS_TOURNAMENT_PARALLELISM", 4),
        vsStockfish    = vsSf,
        stockfishSkill = intEnv("PICHESS_TOURNAMENT_STOCKFISH_SKILL", 5),
        // Default ON — similar bots draw 90%+ of games from
        // startpos, so the diversified opening pool is needed to
        // get any decisive signal at all. Opt out with
        // PICHESS_TOURNAMENT_OPENINGS=false (for testing the
        // bare from-initial behaviour).
        useOpenings    = !sys.env.get("PICHESS_TOURNAMENT_OPENINGS")
                           .exists(_.equalsIgnoreCase("false")),
      )
    }

  private def intEnv(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).getOrElse(default)

  /** Pick the opponent based on config — either Stockfish or
    * another pichess weights snapshot. The Stockfish path is
    * scoped (its subprocess is released on Scope close). */
  private def loadOpponent(cfg: Config): ZIO[Scope, Throwable, Search] =
    if cfg.vsStockfish then
      StockfishSearch.spawn(
        skillLevel = Some(cfg.stockfishSkill),
        label      = s"stockfish-skill${cfg.stockfishSkill}",
      )
    else loadSearch(cfg.champion)

  /** Build a [[Search]] for the given weights-version JSON
    * resource. Uses the array-backed tapered evaluator (the
    * production search path) — same one [[EngineBundle]] wires
    * up. No opening book in the tournament so the search itself
    * is fully exercised. */
  private def loadSearch(version: Int): ZIO[Any, Throwable, Search] =
    WeightsLoader.load(version).map { snapshot =>
      Search.alphaBeta(
        eval = ArrayTaperedEvaluator(snapshot.weights),
        book = OpeningBook.Empty,
      )
    }

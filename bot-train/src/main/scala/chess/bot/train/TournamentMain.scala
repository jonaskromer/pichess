package chess.bot.train

import zio.*

import chess.bot.engine.{
  ArrayTaperedEvaluator, Evaluator, OpeningBook, Search,
  TbAugmentedSearch, WeightsLoader,
}

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
        challengerF <- loadSearch(
                        cfg.challenger, cfg.challengerCmh, cfg.challengerQ,
                        cfg.challengerSee, cfg.challengerId, cfg.challengerNmp,
                        cfg.challengerLmpFut, cfg.challengerSeed, cfg.challengerContHist,
                        cfg.challengerAsp, cfg.challengerNnue, cfg.challengerSe,
                        cfg.challengerNnueEns, cfg.challengerLazySmp,
                        cfg.challengerEvalCache, cfg.challengerFlags,
                        cfg.challengerHybridAlpha,
                      ).flatMap(maybeWrapSyzygy(_, cfg.challengerSyzygy, cfg))
        championF   <- loadOpponent(cfg)
                        .flatMap(maybeWrapSyzygy(_, cfg.championSyzygy, cfg))
        // Time-budget mode: wrap the challenger so every move is a
        // `bestMoveWithBudget(ms)` instead of a fixed-depth search —
        // the exact production search path (same `Search.budgeted`
        // wrapper the live Lichess bot uses). Per-game wrap keeps each
        // isolated game's search fresh.
        challengerBudgeted = cfg.challengerBudgetMs match
          case Some(ms) => () => Search.budgeted(challengerF(), ms)
          case None     => challengerF
        // Resolve the opening pool. Empty pool → all games start
        // from startpos (90%+ draws between similar bots). Non-
        // empty pool → diversifies games for a decisive signal.
        openings <- ZIO.foreach(if cfg.useOpenings then OpeningPool.fens else Vector.empty)(
                      fen => chess.codec.FenParserRegex.parse(fen)
                    )
        // Isolated: a fresh search per game (constant factory for the
        // shared SF subprocess) so concurrent games don't contaminate
        // each other's TT / heuristic tables. This is what makes a
        // parallel Elo readout trustworthy.
        report <- Tournament.playIsolated(
                    challengerFactory = challengerBudgeted,
                    championFactory   = championF,
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
      if cfg.vsStockfish then cfg.stockfishElo.fold(s"Stockfish (Skill Level ${cfg.stockfishSkill})")(e => s"Stockfish (UCI_Elo $e)")
      else s"v${cfg.champion}"
    val parNote =
      // Stockfish runs as a single subprocess — only ONE concurrent
      // bestMove call works. Force parallelism=1 in the Stockfish
      // path; ignore the user setting.
      if cfg.vsStockfish then "1 (forced — single Stockfish subprocess)"
      else cfg.parallelism.toString
    val flagNote =
      val ch = if cfg.challengerFlags.isEmpty then "none" else cfg.challengerFlags.toList.sorted.mkString("+")
      val cm = if cfg.championFlags.isEmpty then "none" else cfg.championFlags.toList.sorted.mkString("+")
      s" [challenger flags: $ch | champion flags: $cm]"
    val searchNote =
      cfg.challengerBudgetMs.fold(s"depth ${cfg.depth}")(ms =>
        s"${ms}ms/move budget (fallback depth ${cfg.depth})")
    s"Tournament: v${cfg.challenger} (challenger) vs $opponent (champion), " +
      s"${cfg.games} games at $searchNote, parallelism $parNote" + flagNote

  private final case class Config(
      challenger: Int,
      champion:   Int,
      games:      Int,
      depth:      Int,
      parallelism: Int,
      vsStockfish: Boolean,
      stockfishSkill: Int,
      stockfishElo:   Option[Int],
      useOpenings: Boolean,
      challengerCmh: Boolean,
      championCmh:   Boolean,
      challengerQ:   Boolean,
      championQ:     Boolean,
      challengerSee: Boolean,
      championSee:   Boolean,
      challengerId:  Boolean,
      championId:    Boolean,
      challengerNmp: Boolean,
      championNmp:   Boolean,
      challengerLmpFut: Boolean,
      championLmpFut:   Boolean,
      challengerSeed:   Boolean,
      championSeed:     Boolean,
      challengerContHist: Boolean,
      championContHist:   Boolean,
      challengerSyzygy:   Boolean,
      championSyzygy:     Boolean,
      syzygyPath:         Option[String],
      syzygyPieceLimit:   Int,
      challengerAsp:      Boolean,
      championAsp:        Boolean,
      challengerNnue:     Boolean,
      championNnue:       Boolean,
      challengerSe:       Boolean,
      championSe:         Boolean,
      challengerNnueEns:  Boolean,
      championNnueEns:    Boolean,
      challengerLazySmp:  Boolean,
      championLazySmp:    Boolean,
      challengerEvalCache: Boolean,
      championEvalCache:   Boolean,
      // Comma-separated set of newer search-heuristic flag names to
      // enable for each side (e.g. "checkExt,rfp,iir"). Lets a
      // single env var A/B any of the post-v8 flags without a
      // dedicated env var per flag. Recognised names are listed in
      // [[applyNewFlags]].
      challengerFlags:     Set[String],
      championFlags:       Set[String],
      // When set, that side uses a HybridEvaluator blending its HCE
      // weights with the single NNUE: score = (1-α)·HCE + α·NNUE.
      // 0.0 = pure HCE, 1.0 = pure NNUE. Lets us A/B whether a
      // HCE-weighted NNUE blend beats pure HCE.
      challengerHybridAlpha: Option[Double],
      championHybridAlpha:   Option[Double],
      // When set, the challenger plays with a per-move TIME BUDGET
      // (iterative deepening to N ms via `bestMoveWithBudget`) instead
      // of the fixed `depth`. This reproduces the live Lichess bot's
      // search exactly, so the harness can rate the real production
      // config rather than a fixed-depth proxy. The opponent is
      // unaffected (Stockfish stays UCI_Elo-anchored).
      challengerBudgetMs:    Option[Long],
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
        // Calibrated SF strength for absolute Elo anchoring. When set,
        // overrides Skill Level (UCI_LimitStrength + UCI_Elo).
        stockfishElo   = sys.env.get("PICHESS_TOURNAMENT_STOCKFISH_ELO").flatMap(_.toIntOption),
        // Default ON — similar bots draw 90%+ of games from
        // startpos, so the diversified opening pool is needed to
        // get any decisive signal at all. Opt out with
        // PICHESS_TOURNAMENT_OPENINGS=false (for testing the
        // bare from-initial behaviour).
        useOpenings    = !sys.env.get("PICHESS_TOURNAMENT_OPENINGS")
                           .exists(_.equalsIgnoreCase("false")),
        // Counter-move heuristic toggles: OFF by default to match
        // the production [[Search.alphaBeta]] default (a 200-game
        // v8-CMH vs v8-noCMH measured ΔElo = -20.9, so CMH ships
        // disabled). Set `..._CHALLENGER_CMH=true` to opt in for
        // a future A/B test at deeper search depth.
        challengerCmh  = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_CMH")
                           .exists(_.equalsIgnoreCase("true")),
        championCmh    = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_CMH")
                           .exists(_.equalsIgnoreCase("true")),
        // Quiescence toggles. Same opt-in shape as CMH.
        challengerQ    = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_Q")
                           .exists(_.equalsIgnoreCase("true")),
        championQ      = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_Q")
                           .exists(_.equalsIgnoreCase("true")),
        // SEE toggles for A/B.
        challengerSee  = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_SEE")
                           .exists(_.equalsIgnoreCase("true")),
        championSee    = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_SEE")
                           .exists(_.equalsIgnoreCase("true")),
        // Iterative-deepening toggles.
        challengerId   = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_ID")
                           .exists(_.equalsIgnoreCase("true")),
        championId     = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_ID")
                           .exists(_.equalsIgnoreCase("true")),
        // Null-move-pruning toggles.
        challengerNmp  = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_NMP")
                           .exists(_.equalsIgnoreCase("true")),
        championNmp    = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_NMP")
                           .exists(_.equalsIgnoreCase("true")),
        // LMP + futility toggles.
        challengerLmpFut = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_LMP")
                             .exists(_.equalsIgnoreCase("true")),
        championLmpFut   = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_LMP")
                             .exists(_.equalsIgnoreCase("true")),
        // CMH-seed toggle. Default ON; opt out with =false.
        challengerSeed = !sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_SEED")
                            .exists(_.equalsIgnoreCase("false")),
        championSeed   = !sys.env.get("PICHESS_TOURNAMENT_CHAMPION_SEED")
                            .exists(_.equalsIgnoreCase("false")),
        // Continuation-history toggle. Default OFF; opt in.
        challengerContHist = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_CONT")
                               .exists(_.equalsIgnoreCase("true")),
        championContHist   = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_CONT")
                               .exists(_.equalsIgnoreCase("true")),
        // Syzygy TB augmentation toggles.
        challengerSyzygy   = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_SYZYGY")
                               .exists(_.equalsIgnoreCase("true")),
        championSyzygy     = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_SYZYGY")
                               .exists(_.equalsIgnoreCase("true")),
        syzygyPath         = sys.env.get("PICHESS_SYZYGY_PATH")
                               .orElse(Some("/tmp/chess-corpus/syzygy"))
                               .filter(p => java.nio.file.Files.isDirectory(java.nio.file.Paths.get(p))),
        syzygyPieceLimit   = sys.env.get("PICHESS_SYZYGY_PIECE_LIMIT")
                               .flatMap(_.toIntOption).getOrElse(5),
        // Aspiration-windows toggles. Defaults OFF; opt in.
        challengerAsp      = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_ASP")
                               .exists(_.equalsIgnoreCase("true")),
        championAsp        = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_ASP")
                               .exists(_.equalsIgnoreCase("true")),
        // NNUE evaluator toggles. When ON, the side uses the baked
        // `/nnue-v1.bin` evaluator instead of the tapered HCE eval.
        challengerNnue     = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_NNUE")
                               .exists(_.equalsIgnoreCase("true")),
        championNnue       = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_NNUE")
                               .exists(_.equalsIgnoreCase("true")),
        challengerSe       = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_SE")
                               .exists(_.equalsIgnoreCase("true")),
        championSe         = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_SE")
                               .exists(_.equalsIgnoreCase("true")),
        challengerNnueEns  = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_NNUE_ENS")
                               .exists(_.equalsIgnoreCase("true")),
        championNnueEns    = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_NNUE_ENS")
                               .exists(_.equalsIgnoreCase("true")),
        challengerLazySmp  = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_SMP")
                               .exists(_.equalsIgnoreCase("true")),
        championLazySmp    = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_SMP")
                               .exists(_.equalsIgnoreCase("true")),
        challengerEvalCache = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_EVCACHE")
                                .exists(_.equalsIgnoreCase("true")),
        championEvalCache   = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_EVCACHE")
                                .exists(_.equalsIgnoreCase("true")),
        challengerFlags     = flagsEnv("PICHESS_TOURNAMENT_CHALLENGER_FLAGS"),
        championFlags       = flagsEnv("PICHESS_TOURNAMENT_CHAMPION_FLAGS"),
        challengerHybridAlpha = sys.env.get("PICHESS_TOURNAMENT_CHALLENGER_HYBRID_ALPHA").flatMap(_.toDoubleOption),
        championHybridAlpha   = sys.env.get("PICHESS_TOURNAMENT_CHAMPION_HYBRID_ALPHA").flatMap(_.toDoubleOption),
        challengerBudgetMs    = sys.env.get("PICHESS_TOURNAMENT_BUDGET_MS").flatMap(_.toLongOption),
      )
    }

  private def intEnv(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).getOrElse(default)

  /** Parse a comma-separated flag-name list from an env var into a
    * lowercased set. Blank / unset → empty set. */
  private def flagsEnv(name: String): Set[String] =
    sys.env.get(name)
      .map(_.split(",").iterator.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet)
      .getOrElse(Set.empty)

  /** Optionally wrap a search in a TB-augmentation layer that
    * delegates low-piece positions (≤ `syzygyPieceLimit`) to a
    * Stockfish-with-Syzygy oracle. The Stockfish subprocess is
    * scoped to the current effect's Scope, so it shuts down when
    * the tournament finishes.
    *
    * When the configured `syzygyPath` is missing (e.g., the user
    * forgot to download the TBs), this no-ops and returns
    * `inner` unchanged — the bot still plays, just without TB
    * augmentation. */
  private def maybeWrapSyzygy(
      inner: () => Search,
      enabled: Boolean,
      cfg: Config,
  ): ZIO[Scope, Throwable, () => Search] =
    if !enabled then ZIO.succeed(inner)
    else cfg.syzygyPath match
      case None       => ZIO.succeed(inner)
      case Some(path) =>
        StockfishSearch
          .spawn(syzygyPath = Some(path), syzygyProbeLimit = cfg.syzygyPieceLimit,
                 label = "syzygy-oracle")
          // One shared TB oracle (single subprocess) wrapping a fresh
          // inner search per game.
          .map(oracle => () => new TbAugmentedSearch(inner(), oracle, cfg.syzygyPieceLimit))

  /** Factory for the opponent — either a single shared Stockfish
    * subprocess (returned by a constant factory; SF can't be
    * duplicated, so those games serialise on its lock) or a per-game
    * pichess search. Scoped so the SF subprocess is released on
    * Scope close. */
  private def loadOpponent(cfg: Config): ZIO[Scope, Throwable, () => Search] =
    if cfg.vsStockfish then
      // UCI_Elo (calibrated) takes precedence over Skill Level when set.
      StockfishSearch.spawn(
        skillLevel = if cfg.stockfishElo.isDefined then None else Some(cfg.stockfishSkill),
        uciElo     = cfg.stockfishElo,
        label      = cfg.stockfishElo.fold(s"stockfish-skill${cfg.stockfishSkill}")(e => s"stockfish-elo$e"),
      ).map(sf => () => sf)
    else loadSearch(
      cfg.champion, cfg.championCmh, cfg.championQ, cfg.championSee,
      cfg.championId, cfg.championNmp, cfg.championLmpFut, cfg.championSeed,
      cfg.championContHist, cfg.championAsp, cfg.championNnue, cfg.championSe,
      cfg.championNnueEns, cfg.championLazySmp, cfg.championEvalCache,
      cfg.championFlags, cfg.championHybridAlpha,
    )

  /** Build a [[Search]] for the given weights-version JSON
    * resource. Uses the array-backed tapered evaluator (the
    * production search path) — same one [[EngineBundle]] wires
    * up. No opening book in the tournament so the search itself
    * is fully exercised. */
  private def loadSearch(
      version: Int,
      counterMoveEnabled: Boolean,
      quiescenceEnabled: Boolean,
      seeEnabled: Boolean,
      iterativeDeepeningEnabled: Boolean,
      nullMovePruningEnabled: Boolean,
      lmpFutilityEnabled: Boolean,
      counterMoveSeedEnabled: Boolean,
      continuationHistoryEnabled: Boolean,
      aspirationWindowsEnabled: Boolean,
      nnueEnabled: Boolean,
      singularExtensionsEnabled: Boolean,
      nnueEnsembleEnabled: Boolean,
      lazySmpEnabled: Boolean,
      evalCacheEnabled: Boolean,
      newFlags: Set[String],
      hybridAlpha: Option[Double] = None,
  ): ZIO[Any, Throwable, () => Search] =
    WeightsLoader.load(version).map { snapshot =>
      // NNUE evaluators are stateless (they allocate fresh
      // accumulators per evaluate() call), so build ONCE and share
      // across all per-game searches — loading the .bin per game
      // would be wasteful. The HCE ArrayTaperedEvaluator, in
      // contrast, reuses an INTERNAL feature buffer across
      // evaluate() calls, so a shared instance would be raced by
      // concurrent games (corrupt features → non-deterministic
      // evals → garbage Elo). It must be per-game, so it's built
      // inside the factory below.
      val sharedNnue: Option[chess.bot.engine.Evaluator] =
        if nnueEnsembleEnabled then
          Some(chess.bot.engine.nnue.NnueEnsemble
            .loadBaked(k = 3)
            .getOrElse(throw new IllegalStateException(
              "NNUE ensemble resources /nnue-ens-v1-s{1..3}.bin not packaged")))
        else if nnueEnabled || hybridAlpha.isDefined then
          Some(chess.bot.engine.nnue.NnueEvaluator
            .loadResource("/nnue-v1.bin")
            .getOrElse(throw new IllegalStateException(
              "NNUE resource /nnue-v1.bin not packaged")))
        else None
      // Validate the requested new-flag names up front — a typo in an
      // A/B config should fail loud, not silently test nothing.
      val unknown = newFlags -- RecognisedNewFlags
      if unknown.nonEmpty then
        throw new IllegalArgumentException(
          s"Unknown tournament flag(s): ${unknown.mkString(", ")}. " +
            s"Recognised: ${RecognisedNewFlags.toList.sorted.mkString(", ")}"
        )
      def on(name: String): Boolean = newFlags.contains(name)
      // Per-game search factory: fresh evaluator (HCE only) + fresh
      // TT + tables each call so concurrent tournament games never
      // share mutable state. The eval cache (when enabled) is ALSO
      // per-game — a shared cache would re-introduce cross-game
      // leakage through the eval. TT is capped well below the
      // production 1M since depth-4 games visit far fewer nodes and
      // we may hold ~2×parallelism of these live.
      () => {
      val rawEval: chess.bot.engine.Evaluator =
        hybridAlpha match
          // Hybrid: per-game HCE (fresh feature buffer) blended with
          // the shared stateless NNUE. sharedNnue is guaranteed Some
          // here (loaded above when hybridAlpha is defined).
          case Some(alpha) =>
            new chess.bot.engine.HybridEvaluator(
              ArrayTaperedEvaluator(snapshot.weights), sharedNnue.get, alpha)
          case None =>
            sharedNnue.getOrElse(ArrayTaperedEvaluator(snapshot.weights))
      val eval: chess.bot.engine.Evaluator =
        if evalCacheEnabled then chess.bot.engine.CachedEvaluator.of(rawEval)
        else rawEval
      Search.alphaBeta(
        eval                         = eval,
        book                         = OpeningBook.Empty,
        maxTtEntries                 = 1 << 18,
        counterMoveEnabled           = counterMoveEnabled,
        quiescenceEnabled            = quiescenceEnabled,
        seeEnabled                   = seeEnabled,
        iterativeDeepeningEnabled    = iterativeDeepeningEnabled,
        nullMovePruningEnabled       = nullMovePruningEnabled,
        lmpFutilityEnabled           = lmpFutilityEnabled,
        counterMoveSeedEnabled       = counterMoveSeedEnabled,
        continuationHistoryEnabled   = continuationHistoryEnabled,
        aspirationWindowsEnabled     = aspirationWindowsEnabled,
        singularExtensionsEnabled    = singularExtensionsEnabled,
        lazySmpEnabled               = lazySmpEnabled,
        // Newer flags driven by the comma-separated set so a single
        // env var A/Bs any of them on top of the same base config.
        checkExtensionEnabled        = on("checkext"),
        nmpVerificationEnabled       = on("nmpverify"),
        pawnCorrHistEnabled          = on("pawncorr"),
        materialCorrHistEnabled      = on("matcorr"),
        iirEnabled                   = on("iir"),
        rfpEnabled                   = on("rfp"),
        razoringEnabled              = on("razoring"),
        deltaPruningEnabled          = on("deltaprune"),
        historyGravityEnabled        = on("histgravity"),
        moveCountPruningEnabled      = on("movecount"),
        doubleExtensionEnabled       = on("doubleext"),
        multiCutEnabled              = on("multicut"),
        ttAgingEnabled               = on("ttaging"),
        multiPlyContinuationEnabled  = on("multiply"),
        underPromotionEnabled        = on("underpromo"),
        timeManagementUpgradeEnabled = on("timemgmt"),
        policyOrderingEnabled        = on("policy"),
      )
      }
    }

  /** Recognised names for the comma-separated CHALLENGER_FLAGS /
    * CHAMPION_FLAGS env vars. Each maps to a post-v8 search flag. */
  private val RecognisedNewFlags: Set[String] = Set(
    "checkext", "nmpverify", "pawncorr", "matcorr", "iir", "rfp",
    "razoring", "deltaprune", "histgravity", "movecount", "doubleext",
    "multicut", "ttaging", "multiply", "underpromo", "timemgmt",
    "policy",
  )

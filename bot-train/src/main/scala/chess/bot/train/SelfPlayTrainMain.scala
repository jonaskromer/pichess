package chess.bot.train

import java.nio.file.Paths

import zio.*

import chess.bot.engine.{
  ArrayTaperedEvaluator, OpeningBook, Search, TaperedFeatureExtractor,
  WeightSnapshot, WeightsLoader,
}
import chess.codec.FenParserRegex
import chess.model.board.{GameState, Move}

/** Reinforcement-style re-training: generate a diverse self-play
  * corpus by pitting a "hero" search (a chosen weights version +
  * search flags) against a rotation of opponents — older bot
  * iterations and Stockfish at several skill levels — then Texel-tune
  * the evaluator on the outcome-labelled positions and write a new
  * `weights/vN.json`.
  *
  * Unlike [[TrainMain]] (which Texel-tunes against a downloaded PGN
  * master corpus) this needs no external data: it manufactures its
  * own labelled positions from games it plays. Opponent + opening
  * diversity keeps the position distribution broad so the tuned eval
  * generalises rather than overfitting to one opponent's style.
  *
  * Run via:
  * {{{
  *   sbt 'botTrain/runMain chess.bot.train.SelfPlayTrainMain'
  * }}}
  *
  * Env vars:
  *   - `PICHESS_SPT_HERO_VERSION`   weights version the hero uses (default 8)
  *   - `PICHESS_SPT_HERO_FLAGS`     comma-sep search flags for the hero
  *                                  (default `checkext` — the one flag that
  *                                  measured clearly +Elo at parallelism=1)
  *   - `PICHESS_SPT_BOT_OPPONENTS`  comma-sep older versions to face
  *                                  (default `8,5,4`)
  *   - `PICHESS_SPT_SF_SKILLS`      comma-sep Stockfish skill levels
  *                                  (default `2,4,6`; empty disables SF)
  *   - `PICHESS_SPT_GAMES_PER_OPP`  games per opponent (default 60)
  *   - `PICHESS_SPT_DEPTH`          search depth (default 4)
  *   - `PICHESS_SPT_NOISE_PLIES`    random plies after the book opening for
  *                                  diversity (default 4)
  *   - `PICHESS_SPT_PARALLELISM`    bot-vs-bot game parallelism (default 6;
  *                                  SF games always run sequentially)
  *   - `PICHESS_SPT_NEXT_VERSION`   output weights version (default 9)
  *   - `PICHESS_SPT_OUT`            output path (default
  *                                  `bot-engine/src/main/resources/weights/v9.json`)
  *   - `PICHESS_SPT_MAX_ITERATIONS` tuner cap (default 60)
  *   - `PICHESS_SPT_INITIAL_STEP`   tuner starting step cp (default 8 —
  *                                  small, since we seed from a tuned v8)
  */
object SelfPlayTrainMain extends ZIOAppDefault:

  private val extractor = TaperedFeatureExtractor.full

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] = program

  private final case class Config(
      heroVersion: Int,
      heroFlags: Set[String],
      botOpponents: List[Int],
      sfSkills: List[Int],
      gamesPerOpp: Int,
      depth: Int,
      noisePlies: Int,
      parallelism: Int,
      nextVersion: Int,
      outPath: String,
      maxIterations: Int,
      initialStep: Int,
  )

  private def program: ZIO[Scope, Throwable, Unit] =
    for
      cfg  <- readConfig
      _    <- ZIO.logInfo(label(cfg))
      heroWeights <- WeightsLoader.load(cfg.heroVersion).map(_.weights)
      hero  = buildSearch(heroWeights, cfg.heroFlags)
      openings <- ZIO.foreach(OpeningPool.fens)(FenParserRegex.parse)
      // 1) Bot-vs-bot games (parallelisable).
      botRows <- ZIO.foreach(cfg.botOpponents) { v =>
                   WeightsLoader.load(v).map(_.weights).flatMap { w =>
                     val opp = buildSearch(w, Set.empty)
                     generateBotGames(hero, opp, s"v$v", cfg, openings.toVector)
                   }
                 }.map(_.flatten)
      // 2) Stockfish games (sequential — one subprocess per skill).
      sfRows <- ZIO.foreach(cfg.sfSkills) { skill =>
                  generateSfGames(hero, skill, cfg, openings.toVector)
                }.map(_.flatten)
      allRows = botRows ++ sfRows
      _    <- ZIO.logInfo(s"Collected ${allRows.size} labelled positions across " +
                s"${cfg.botOpponents.size} bot opponents + ${cfg.sfSkills.size} SF levels")
      // 3) Texel-tune. Seed the initial vector with EVERY feature
      //    name the extractor can emit (threats at 0 when absent from
      //    the hero's older snapshot) so the tuner can learn weights
      //    for features the seed version predates — e.g. the
      //    threat_by_* terms added after v8 was tuned. Keys only in
      //    `initial` are adjustable; missing ones stay frozen at 0.
      tunerInitial = TaperedFeatureExtractor.allFeatureNames
                       .map(k => k -> heroWeights.getOrElse(k, 0)).toMap
      newKeys = tunerInitial.size - heroWeights.size
      _    <- ZIO.logInfo(s"Tuner seed: ${tunerInitial.size} keys " +
                s"($newKeys new since v${cfg.heroVersion})")
      samples = allRows.flatMap(r => CorpusTrainer.toSample(r, extractor))
      _    <- ZIO.when(samples.isEmpty)(
                ZIO.fail(new RuntimeException("No usable samples generated")))
      lossBefore = TexelTuner.totalLoss(samples.iterator, tunerInitial, 0.4)
      tuned = TexelTuner.tune(samples.iterator, tunerInitial, K = 0.4,
                maxIterations = cfg.maxIterations, initialStep = cfg.initialStep)
      snapshot = WeightSnapshot(version = cfg.nextVersion, weights = tuned.weights)
      _    <- WeightsLoader.writeFile(snapshot, Paths.get(cfg.outPath))
      _    <- ZIO.logInfo(
                f"Tuned v${cfg.nextVersion}: samples=${samples.size} " +
                f"lossBefore=$lossBefore%.5f lossAfter=${tuned.finalLoss}%.5f " +
                f"iterations=${tuned.iterations} → wrote ${cfg.outPath}")
    yield ()

  private def buildSearch(weights: Map[String, Int], flags: Set[String]): Search =
    val eval = ArrayTaperedEvaluator(weights)
    def on(n: String) = flags.contains(n)
    Search.alphaBeta(
      eval = eval,
      book = OpeningBook.Empty,
      // Production base config (matches the tournament A/B base).
      quiescenceEnabled = true,
      seeEnabled = true,
      nullMovePruningEnabled = true,
      singularExtensionsEnabled = true,
      checkExtensionEnabled = on("checkext"),
      pawnCorrHistEnabled = on("pawncorr"),
      iirEnabled = on("iir"),
      rfpEnabled = on("rfp"),
    )

  /** Play `gamesPerOpp` hero-vs-opponent games (color-alternating,
    * opening-diversified) and return all outcome-labelled rows. */
  private def generateBotGames(
      hero: Search, opp: Search, oppTag: String,
      cfg: Config, openings: Vector[GameState],
  ): UIO[Vector[chess.bot.data.TrainingRow]] =
    ZIO.foreachPar(0 until cfg.gamesPerOpp) { i =>
      val start = diversifiedOpening(openings, i)
      val (white, black) = if i % 2 == 0 then (hero, opp) else (opp, hero)
      SelfPlay.playGame(white, black, cfg.depth, initialState = start)
        .map(SelfPlay.gameToTrainingRows)
    }.withParallelism(cfg.parallelism)
      .map(_.flatten.toVector)
      .tap(rows => ZIO.logInfo(s"  vs $oppTag: ${rows.size} rows from ${cfg.gamesPerOpp} games"))

  /** Play hero-vs-Stockfish games sequentially (single SF subprocess). */
  private def generateSfGames(
      hero: Search, skill: Int, cfg: Config, openings: Vector[GameState],
  ): ZIO[Scope, Throwable, Vector[chess.bot.data.TrainingRow]] =
    StockfishSearch.spawn(skillLevel = Some(skill), label = s"sf-skill$skill").flatMap { sf =>
      ZIO.foreach(0 until cfg.gamesPerOpp) { i =>
        val start = diversifiedOpening(openings, i)
        val (white, black) = if i % 2 == 0 then (hero, sf: Search) else (sf: Search, hero)
        SelfPlay.playGame(white, black, cfg.depth, initialState = start)
          .map(SelfPlay.gameToTrainingRows)
      }.map(_.flatten.toVector)
        .tap(rows => ZIO.logInfo(s"  vs SF-skill$skill: ${rows.size} rows from ${cfg.gamesPerOpp} games"))
    }

  /** Pick an opening from the pool (paired by color via i/2) and play
    * `noisePlies` random legal moves for position diversity. */
  private def diversifiedOpening(openings: Vector[GameState], i: Int): GameState =
    val base = if openings.isEmpty then GameState.initial else openings((i / 2) % openings.size)
    val rng = new scala.util.Random(0x5EEDL ^ i.toLong)
    applyNoise(base, n = 4, rng)

  private def applyNoise(state: GameState, n: Int, rng: scala.util.Random): GameState =
    if n <= 0 then state
    else
      val index = chess.model.rules.MoveValidator.legalDestinationsIndexSync(state)
      val moves = index.toVector.flatMap { case (from, tos) =>
        tos.map(to => Move(from, to, promotion = None))
      }
      if moves.isEmpty then state
      else
        chess.model.rules.Game.applyMoveCoreSync(state, moves(rng.nextInt(moves.size))) match
          case Some(next) => applyNoise(next, n - 1, rng)
          case None       => state

  private def label(cfg: Config): String =
    s"SelfPlayTrain: hero=v${cfg.heroVersion}[${cfg.heroFlags.toList.sorted.mkString("+")}] " +
      s"vs bots=${cfg.botOpponents.mkString(",")} + SF=${cfg.sfSkills.mkString(",")}, " +
      s"${cfg.gamesPerOpp} games/opp at depth ${cfg.depth}, " +
      s"noise=${cfg.noisePlies} → v${cfg.nextVersion}"

  private def readConfig: UIO[Config] = ZIO.succeed {
    def intEnv(n: String, d: Int) = sys.env.get(n).flatMap(_.toIntOption).getOrElse(d)
    def listEnv(n: String, d: List[Int]) =
      sys.env.get(n).map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty)
        .flatMap(_.toIntOption).toList).getOrElse(d)
    def flagsEnv(n: String, d: Set[String]) =
      sys.env.get(n).map(_.split(",").iterator.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet)
        .getOrElse(d)
    Config(
      heroVersion   = intEnv("PICHESS_SPT_HERO_VERSION", 8),
      heroFlags     = flagsEnv("PICHESS_SPT_HERO_FLAGS", Set("checkext")),
      botOpponents  = listEnv("PICHESS_SPT_BOT_OPPONENTS", List(8, 5, 4)),
      sfSkills      = listEnv("PICHESS_SPT_SF_SKILLS", List(2, 4, 6)),
      gamesPerOpp   = intEnv("PICHESS_SPT_GAMES_PER_OPP", 60),
      depth         = intEnv("PICHESS_SPT_DEPTH", 4),
      noisePlies    = intEnv("PICHESS_SPT_NOISE_PLIES", 4),
      parallelism   = intEnv("PICHESS_SPT_PARALLELISM", 6),
      nextVersion   = intEnv("PICHESS_SPT_NEXT_VERSION", 9),
      outPath       = sys.env.getOrElse("PICHESS_SPT_OUT",
                        "bot-engine/src/main/resources/weights/v9.json"),
      maxIterations = intEnv("PICHESS_SPT_MAX_ITERATIONS", 60),
      initialStep   = intEnv("PICHESS_SPT_INITIAL_STEP", 8),
    )
  }

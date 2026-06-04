package chess.bot.train

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*

import chess.bot.data.{BookRepo, Db, IngestedFilesRepo, TrainingRepo, WeightsRepo}
import chess.bot.engine.{WeightSnapshot, WeightsLoader}

/** End-to-end "train the bot" entry point.
  *
  * Reads source-tagged PGN files from configurable directories,
  * ingests them into a DuckDB file with the right per-source quality
  * weights, runs the Texel tuner against the accumulated quiet
  * positions, and writes a new `weights/vN.json` resource the bot
  * loads at startup.
  *
  * Run via:
  * {{{
  *   sbt 'botTrain/runMain chess.bot.train.TrainMain'
  * }}}
  *
  * Configurable via environment variables:
  *   - `PICHESS_TWIC_DIR`         (default `/tmp/chess-corpus/twic-pgn`)
  *   - `PICHESS_PGN_MENTOR_DIR`   (default `/tmp/chess-corpus/pgn-mentor-pgn`)
  *   - `PICHESS_DUCKDB_PATH`      (default `./chess-bot-training.duckdb`)
  *   - `PICHESS_OUTPUT_WEIGHTS`   (default
  *       `bot-engine/src/main/resources/weights/v2.json`)
  *   - `PICHESS_NEXT_VERSION`     (default `2`)
  *
  * Missing directories are silently skipped — running with just one
  * source is fine, the run reports "ingested 0 games for X" and
  * moves on.
  */
object TrainMain extends ZIOAppDefault:

  // Per-source default directories. Mirror the `mkdir -p` paths used
  // by the corpus-download script so a fresh checkout + download
  // works out of the box.
  private val defaultTwicDir       = "/tmp/chess-corpus/twic-pgn"
  private val defaultPgnMentorDir  = "/tmp/chess-corpus/pgn-mentor-pgn"
  private val defaultDbPath        = "./chess-bot-training.duckdb"
  private val defaultOutputPath    =
    "bot-engine/src/main/resources/weights/v2.json"
  private val defaultNextVersion   = 2

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    program

  // `def` (not val) so its references to `readConfig`, `collectInputs`,
  // and `fallbackInitial` are resolved at call time, not at object-init.
  // With val + source-order initialisation those forward references
  // resolve to null and crash on first run.
  private def program: ZIO[Scope, Throwable, Unit] =
    for
      cfg     <- readConfig
      _       <- ZIO.logInfo(s"Config: $cfg")
      inputs  <- collectInputs(cfg)
      _       <- ZIO.logInfo(s"Found ${inputs.size} PGN files across all sources.")
      conn    <- Db.open(Db.Config(cfg.dbPath))
      repos    = CorpusTrainer.Repos(
                   book          = BookRepo.duckdb(conn),
                   training      = TrainingRepo.duckdb(conn),
                   weights       = WeightsRepo.duckdb(conn),
                   ingestedFiles = IngestedFilesRepo.duckdb(conn),
                   connection    = conn,
                 )
      stats   <- CorpusTrainer.ingestAll(inputs, repos)
      _       <- ZIO.logInfo(s"Ingest: $stats")
      initial <- WeightsLoader.load(cfg.nextVersion - 1).catchAll { err =>
                   ZIO.logWarning(
                     s"Couldn't load weights v${cfg.nextVersion - 1}: ${err.getMessage}",
                   ) *> ZIO.succeed(fallbackInitial)
                 }
      _       <- ZIO.logInfo(s"Initial weights v${initial.version}: ${initial.weights}")
      result  <- CorpusTrainer.tuneAndPersist(
                   repos       = repos,
                   initial     = initial.weights,
                   nextVersion = cfg.nextVersion,
                   writeJsonTo = Some(Paths.get(cfg.outputPath)),
                   ingestStats = stats,
                 )
      _       <- ZIO.logInfo(
                   s"Tuned in ${result.iterations} iterations: " +
                     s"loss ${"%.6f".format(result.lossBefore)} → " +
                     s"${"%.6f".format(result.lossAfter)}",
                 )
      _       <- ZIO.logInfo(s"Saved v${result.snapshot.version}: ${result.snapshot.weights}")
      _       <- ZIO.logInfo(s"Wrote ${cfg.outputPath}")
    yield ()

  private final case class Config(
      twicDir: String,
      pgnMentorDir: String,
      dbPath: String,
      outputPath: String,
      nextVersion: Int,
  )

  private def readConfig: UIO[Config] =
    ZIO.succeed(
      Config(
        twicDir      = sys.env.getOrElse("PICHESS_TWIC_DIR",       defaultTwicDir),
        pgnMentorDir = sys.env.getOrElse("PICHESS_PGN_MENTOR_DIR", defaultPgnMentorDir),
        dbPath       = sys.env.getOrElse("PICHESS_DUCKDB_PATH",    defaultDbPath),
        outputPath   = sys.env.getOrElse("PICHESS_OUTPUT_WEIGHTS", defaultOutputPath),
        nextVersion  = sys.env.get("PICHESS_NEXT_VERSION")
                         .flatMap(_.toIntOption)
                         .getOrElse(defaultNextVersion),
      )
    )

  /** Enumerate PGN files in each source directory + tag with quality. */
  private def collectInputs(cfg: Config): UIO[List[CorpusTrainer.CorpusInput]] =
    for
      twic   <- listPgnFiles(cfg.twicDir).map(_.map(p =>
                  CorpusTrainer.CorpusInput(CorpusSource.Twic, p)
                ))
      mentor <- listPgnFiles(cfg.pgnMentorDir).map(_.map(p =>
                  CorpusTrainer.CorpusInput(CorpusSource.PgnMentor, p)
                ))
    yield twic ++ mentor

  /** List .pgn files in `dir`. Returns Nil if the directory doesn't
    * exist — a missing source is informational, not a failure. */
  private def listPgnFiles(dir: String): UIO[List[Path]] =
    ZIO
      .attempt {
        val p = Paths.get(dir)
        if !Files.isDirectory(p) then Nil
        else
          Using.resource(Files.list(p)) { stream =>
            stream.iterator.asScala.toList
              .filter(_.toString.endsWith(".pgn"))
              .sortBy(_.toString)
          }
      }
      .orElseSucceed(Nil)

  /** Material-only seed used when no prior weights resource exists. */
  private val fallbackInitial: WeightSnapshot = WeightSnapshot(
    version = 0,
    weights = Map(
      "pawn"   -> 100,
      "knight" -> 320,
      "bishop" -> 330,
      "rook"   -> 500,
      "queen"  -> 900,
    ),
  )

  private object Using:
    /** Try-with-resources for any AutoCloseable. */
    def resource[A <: AutoCloseable, B](r: A)(f: A => B): B =
      try f(r) finally r.close()

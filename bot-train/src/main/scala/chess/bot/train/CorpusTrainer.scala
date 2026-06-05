package chess.bot.train

import java.nio.file.{Files, Path}

import scala.util.Using

import zio.*
import zio.json.*

import java.sql.Connection

import chess.bot.data.{BookRepo, Db, IngestedFilesRepo, TrainingRepo, TrainingRow, WeightsRepo}
import chess.bot.engine.{FeatureExtractor, TaperedFeatureExtractor, WeightSnapshot, WeightsLoader}
import chess.codec.FenParserRegex

/** End-to-end training orchestration: ingest one or more PGN
  * corpora into DuckDB tagging each with its source quality, sample
  * positions back out, run the Texel tuner, persist the new weight
  * snapshot, and write a committable `vN.json` next to the
  * `bot-engine/src/main/resources/weights/` defaults.
  *
  * Pure plumbing — every step is built on the lower-level
  * components ([[PgnIngest]], [[TexelTuner]], [[WeightsLoader]],
  * [[BookRepo]], [[TrainingRepo]], [[WeightsRepo]]) so each is
  * testable in isolation. This module is the "runbook" that wires
  * them.
  *
  * Typical usage from a `Main` (or REPL):
  * {{{
  *   ZIO.scoped {
  *     for
  *       conn  <- Db.open(Db.Config("./chess-bot.duckdb"))
  *       repos  = Repos(BookRepo.duckdb(conn),
  *                      TrainingRepo.duckdb(conn),
  *                      WeightsRepo.duckdb(conn))
  *       _     <- CorpusTrainer.ingestAll(List(
  *                  CorpusInput(CorpusSource.PgnMentor, Path.of("kasparov.pgn")),
  *                  CorpusInput(CorpusSource.Twic,      Path.of("twic1567.pgn")),
  *                ), repos)
  *       initial <- WeightsLoader.load(version = 1)
  *       result <- CorpusTrainer.tuneAndPersist(
  *                   repos, initial.weights, nextVersion = 2,
  *                   writeJsonTo = Some(Path.of(
  *                     "bot-engine/src/main/resources/weights/v2.json"
  *                   )),
  *                 )
  *     yield result
  *   }
  * }}}
  */
object CorpusTrainer:

  /** One ingest target: a PGN file on disk + its quality source. */
  final case class CorpusInput(source: CorpusSource, path: Path)

  /** Bundle of the repos the trainer talks to. Kept as a single
    * value so test code constructs once and threads through.
    *
    * `connection` is exposed so the resumable-ingest path can wrap
    * per-file work in a transaction via `Db.withTransaction`. Tests
    * that don't care about resumability can pass any connection
    * (e.g. the same one the BookRepo/TrainingRepo/etc. are built on,
    * which is the typical setup). */
  final case class Repos(
      book: BookRepo,
      training: TrainingRepo,
      weights: WeightsRepo,
      ingestedFiles: IngestedFilesRepo,
      connection: Connection,
  )

  /** Summary of one training run. */
  final case class TrainResult(
      ingestStats: PgnIngest.Stats,
      samplesUsed: Long,
      lossBefore: Double,
      lossAfter: Double,
      iterations: Int,
      snapshot: WeightSnapshot,
  )

  /** Ingest every PGN file in `inputs` into DuckDB with the right
    * quality weight per source.
    *
    * Each file is processed inside its own transaction with the
    * `ingested_files` marker written as the last operation — so a
    * crash mid-file rolls back cleanly and the file gets re-attempted
    * on the next run. Files already marked as ingested are skipped
    * up-front, which is what makes the trainer resumable.
    *
    * Failures on individual files (parse error in a non-ingest
    * stage, I/O error reading the file, ...) are logged but don't
    * abort the run — a single bad file shouldn't waste the hours
    * already spent on the rest of the corpus.
    */
  def ingestAll(inputs: List[CorpusInput], repos: Repos): UIO[PgnIngest.Stats] =
    ZIO.foldLeft(inputs)(PgnIngest.Stats.Zero) { (acc, in) =>
      ingestOneFile(in, repos).map(acc + _)
    }

  /** Process one corpus file: skip if already ingested, otherwise
    * read, ingest within a transaction, mark complete. Errors are
    * caught + logged so the outer loop continues. */
  private def ingestOneFile(
      in: CorpusInput,
      repos: Repos,
  ): UIO[PgnIngest.Stats] =
    val pathKey = in.path.toAbsolutePath.toString
    repos.ingestedFiles.isIngested(pathKey).flatMap {
      case true =>
        ZIO.logInfo(s"skip $pathKey — already ingested").as(PgnIngest.Stats.Zero)
      case false =>
        Db.withTransaction(repos.connection) {
          for
            pgn   <- readFile(in.path)
            stats <- PgnIngest.ingestMany(pgn, repos.book, repos.training, in.source.quality)
            _     <- repos.ingestedFiles.markIngested(pathKey, stats.games)
          yield stats
        }.foldZIO(
          err => ZIO.logWarning(s"corpus $pathKey: ${err.getMessage}").as(PgnIngest.Stats.Zero),
          stats => ZIO.logInfo(s"ingested $pathKey: $stats").as(stats),
        )
    }

  /** Read training rows from DuckDB, turn each into a
    * [[TexelTuner.Sample]], run the tuner with `initial` as the
    * starting weight vector, and persist the result as version
    * `nextVersion` (DB save + optional JSON write).
    *
    * The JSON write target is typically
    * `bot-engine/src/main/resources/weights/vN.json` so the committed
    * resource updates in lock-step with the DB. Pass `None` to skip
    * the file write (DB-only).
    */
  def tuneAndPersist(
      repos: Repos,
      initial: Map[String, Int],
      nextVersion: Int,
      writeJsonTo: Option[Path] = None,
      K: Double = 0.4,
      maxIterations: Int = 100,
      initialStep: Int = 16,
      ingestStats: PgnIngest.Stats = PgnIngest.Stats.Zero,
      extractor: TaperedFeatureExtractor = TaperedFeatureExtractor.full,
      subsample: Long = Long.MaxValue,
  ): IO[Throwable, TrainResult] =
    for
      rows <- repos.training.streamQuiet.runCollect
      effectiveRows = subsampleRows(rows, subsample)
      samples = effectiveRows.flatMap(r => toSample(r, extractor))
      _       <- ZIO.when(samples.isEmpty)(
                   ZIO.fail(new RuntimeException(
                     "No usable training positions — ingest a corpus first " +
                       "(richer-features path also needs the new FEN column).",
                   ))
                 )
      lossBefore = TexelTuner.totalLoss(samples, initial, K)
      tuned    = TexelTuner.tune(samples, initial, K, maxIterations, initialStep)
      snapshot = WeightSnapshot(version = nextVersion, weights = tuned.weights)
      _       <- repos.weights.save(snapshot)
      _       <- writeJsonTo.fold(ZIO.unit)(p => WeightsLoader.writeFile(snapshot, p))
    yield TrainResult(
      ingestStats = ingestStats,
      samplesUsed = samples.size.toLong,
      lossBefore  = lossBefore,
      lossAfter   = tuned.finalLoss,
      iterations  = tuned.iterations,
      snapshot    = snapshot,
    )

  /** Deterministic stride-based subsample. `cap >= rows.size` returns
    * `rows` unchanged; otherwise every `ceil(rows.size / cap)`-th row
    * is kept. The `Chunk` zipping isn't shuffled — we accept the
    * bias for the simplicity / reproducibility win (same rows
    * sampled across re-runs on the same corpus). */
  private[train] def subsampleRows(
      rows: Chunk[TrainingRow],
      cap: Long,
  ): Chunk[TrainingRow] =
    if cap >= rows.size then rows
    else
      val stride = ((rows.size + cap - 1) / cap).toInt
      Chunk.fromIterable(rows.zipWithIndex.collect {
        case (r, i) if i % stride == 0 => r
      })

  /** Convert one persisted [[TrainingRow]] into a tuner
    * [[TexelTuner.Sample]] using `extractor`.
    *
    * The tapered extractor needs the row's stored FEN to re-build
    * the [[chess.model.board.GameState]]; rows without one (legacy
    * schema before the FEN column landed) return `None` and the
    * tuner sees one fewer sample. */
  private[train] def toSample(
      row: TrainingRow,
      extractor: TaperedFeatureExtractor = TaperedFeatureExtractor.full,
  ): Option[TexelTuner.Sample] =
    row.fen.flatMap { fen =>
      // Unsafe-run is fine — FenParserRegex.parse is sync CPU work;
      // the IO wrapping is purely for error typing.
      zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(FenParserRegex.parse(fen).either)
          .getOrThrow()
          .toOption
          .map { state =>
            TexelTuner.Sample(
              features = extractor.features(state),
              outcome  = row.outcome.toDouble,
              weight   = row.weight.toDouble,
            )
          }
      }
    }

  /** Read a PGN file as UTF-8. Used as the read step in [[ingestAll]];
    * pulled out for testability + a clean failure surface. */
  private[train] def readFile(path: Path): IO[Throwable, String] =
    ZIO.attemptBlocking {
      Using.resource(scala.io.Source.fromFile(path.toFile, "UTF-8"))(_.mkString)
    }

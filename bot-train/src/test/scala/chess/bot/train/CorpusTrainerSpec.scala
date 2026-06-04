package chess.bot.train

import java.nio.file.Files

import zio.*
import zio.test.*

import chess.bot.data.{BookRepo, Db, IngestedFilesRepo, TrainingRepo, TrainingRow, WeightsRepo}

object CorpusTrainerSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")

  private val samplePgn =
    """[Event "Sample"]
      |[Result "1-0"]
      |[WhiteElo "2200"]
      |[BlackElo "2100"]
      |
      |1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 1-0
      |""".stripMargin

  /** Build a freshly-scoped repo bundle backed by an in-memory DuckDB. */
  private def freshRepos
      : ZIO[Scope, Throwable, CorpusTrainer.Repos] =
    Db.open(memoryCfg).map { conn =>
      CorpusTrainer.Repos(
        book          = BookRepo.duckdb(conn),
        training      = TrainingRepo.duckdb(conn),
        weights       = WeightsRepo.duckdb(conn),
        ingestedFiles = IngestedFilesRepo.duckdb(conn),
        connection    = conn,
      )
    }

  def spec = suite("CorpusTrainer")(
    suite("toSample")(
      test("preserves material-feature counts from a TrainingRow") {
        val row = TrainingRow(
          zobrist = 1L, outcome = 1.0f, quiet = true, weight = 0.7f,
          pawnDiff = 1, knightDiff = -1, bishopDiff = 0,
          rookDiff = 0, queenDiff = 1,
        )
        val sample = CorpusTrainer.toSample(row)
        assertTrue(
          sample.features("pawn")   == 1,
          sample.features("knight") == -1,
          sample.features("queen")  == 1,
          sample.outcome == 1.0,
          // Float→Double widening loses precision (0.7f → 0.6999…);
          // use a tight epsilon comparison instead of strict ==.
          math.abs(sample.weight - 0.7) < 1e-5,
        )
      },
    ),
    suite("ingestAll")(
      test("ingests multiple files with their respective qualities") {
        ZIO.scoped {
          for
            tmp <- ZIO.attempt(Files.createTempDirectory("pichess-corpus-"))
            f1   = tmp.resolve("a.pgn")
            f2   = tmp.resolve("b.pgn")
            _   <- ZIO.attempt(Files.writeString(f1, samplePgn))
            _   <- ZIO.attempt(Files.writeString(f2, samplePgn))
            repos <- freshRepos
            stats <- CorpusTrainer.ingestAll(
                       List(
                         CorpusTrainer.CorpusInput(CorpusSource.PgnMentor, f1),
                         CorpusTrainer.CorpusInput(CorpusSource.Twic,      f2),
                       ),
                       repos,
                     )
            // Verify both quality tiers landed in the DB by sampling.
            rows <- repos.training.streamQuiet.runCollect
            _   <- ZIO.attempt(Files.delete(f1))
            _   <- ZIO.attempt(Files.delete(f2))
            _   <- ZIO.attempt(Files.delete(tmp))
          yield assertTrue(
            stats.games == 2L,
            // Both quality weights present in the rows we just wrote.
            rows.exists(_.weight == CorpusSource.PgnMentor.quality),
            rows.exists(_.weight == CorpusSource.Twic.quality),
          )
        }
      },
      test("missing files are logged + skipped, not fatal") {
        ZIO.scoped {
          for
            repos <- freshRepos
            stats <- CorpusTrainer.ingestAll(
                       List(CorpusTrainer.CorpusInput(
                         CorpusSource.Lichess,
                         java.nio.file.Path.of("/tmp/pichess-nonexistent.pgn"),
                       )),
                       repos,
                     )
          yield assertTrue(stats == PgnIngest.Stats.Zero)
        }
      },
      test("re-running over the same file is a no-op (resumability)") {
        // First run ingests the file + marks it. Second run sees the
        // marker and skips → no double-counting.
        ZIO.scoped {
          for
            tmp <- ZIO.attempt(Files.createTempDirectory("pichess-corpus-"))
            f1   = tmp.resolve("a.pgn")
            _   <- ZIO.attempt(Files.writeString(f1, samplePgn))
            repos <- freshRepos
            input  = CorpusTrainer.CorpusInput(CorpusSource.PgnMentor, f1)
            firstRun  <- CorpusTrainer.ingestAll(List(input), repos)
            secondRun <- CorpusTrainer.ingestAll(List(input), repos)
            rowCount  <- repos.training.count
            _   <- ZIO.attempt(Files.delete(f1))
            _   <- ZIO.attempt(Files.delete(tmp))
          yield assertTrue(
            firstRun.games  == 1L,
            secondRun.games == 0L,   // skipped, not re-ingested
            // The row count after BOTH runs should match what was
            // written in the first run — no duplicates.
            rowCount == firstRun.trainingRows,
          )
        }
      },
      test("isIngested marker survives across CorpusTrainer.ingestAll invocations") {
        ZIO.scoped {
          for
            tmp <- ZIO.attempt(Files.createTempDirectory("pichess-corpus-"))
            f1   = tmp.resolve("b.pgn")
            _   <- ZIO.attempt(Files.writeString(f1, samplePgn))
            repos <- freshRepos
            _    <- CorpusTrainer.ingestAll(
                      List(CorpusTrainer.CorpusInput(CorpusSource.Twic, f1)),
                      repos,
                    )
            wasMarked <- repos.ingestedFiles.isIngested(f1.toAbsolutePath.toString)
            _   <- ZIO.attempt(Files.delete(f1))
            _   <- ZIO.attempt(Files.delete(tmp))
          yield assertTrue(wasMarked)
        }
      },
    ),
    suite("tuneAndPersist")(
      test("trains weights against ingested rows + saves snapshot + writes JSON") {
        ZIO.scoped {
          for
            tmp     <- ZIO.attempt(Files.createTempDirectory("pichess-out-"))
            corpus   = tmp.resolve("c.pgn")
            jsonOut  = tmp.resolve("vN.json")
            _       <- ZIO.attempt(Files.writeString(corpus, samplePgn))
            repos   <- freshRepos
            _       <- CorpusTrainer.ingestAll(
                         List(CorpusTrainer.CorpusInput(CorpusSource.PgnMentor, corpus)),
                         repos,
                       )
            result  <- CorpusTrainer.tuneAndPersist(
                         repos,
                         initial = Map("pawn" -> 50, "knight" -> 200,
                                       "bishop" -> 200, "rook" -> 300, "queen" -> 500),
                         nextVersion = 2,
                         writeJsonTo = Some(jsonOut),
                         maxIterations = 5,
                       )
            // Snapshot persisted in DB.
            stored <- repos.weights.load(2)
            // JSON file written.
            jsonExists <- ZIO.attempt(Files.exists(jsonOut))
            _ <- ZIO.attempt(Files.deleteIfExists(corpus))
            _ <- ZIO.attempt(Files.deleteIfExists(jsonOut))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
          yield assertTrue(
            result.snapshot.version == 2,
            result.samplesUsed > 0L,
            stored.exists(_.weights == result.snapshot.weights),
            jsonExists,
          )
        }
      },
      test("fails when there are no quiet training positions") {
        ZIO.scoped {
          for
            repos  <- freshRepos
            result <- CorpusTrainer
                        .tuneAndPersist(
                          repos,
                          initial     = Map("pawn" -> 100),
                          nextVersion = 1,
                        )
                        .exit
          yield assertTrue(result.isFailure)
        }
      },
    ),
  )

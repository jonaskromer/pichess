package chess.bot.data

import zio.*
import zio.test.*

object TrainingRepoSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")

  def spec = suite("TrainingRepo")(
    test("count starts at 0 on a fresh table") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = TrainingRepo.duckdb(conn)
          n    <- repo.count
        yield assertTrue(n == 0L)
      }
    },
    test("appendBatch + count reflects the inserted rows") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = TrainingRepo.duckdb(conn)
          _    <- repo.appendBatch(Chunk(
                    TrainingRow(1L, 1.0f, quiet = true),
                    TrainingRow(2L, 0.5f, quiet = true),
                    TrainingRow(3L, 0.0f, quiet = false),
                  ))
          n    <- repo.count
        yield assertTrue(n == 3L)
      }
    },
    test("streamQuiet emits only rows where quiet = true") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = TrainingRepo.duckdb(conn)
          _    <- repo.appendBatch(Chunk(
                    TrainingRow(10L, 1.0f, quiet = true),
                    TrainingRow(20L, 0.0f, quiet = false),
                    TrainingRow(30L, 0.5f, quiet = true),
                  ))
          rows <- repo.streamQuiet.runCollect
        yield assertTrue(
          rows.size == 2,
          rows.forall(_.quiet),
          rows.exists(_.zobrist == 10L),
          rows.exists(_.zobrist == 30L),
          !rows.exists(_.zobrist == 20L),
        )
      }
    },
    test("streamQuiet on an empty table emits nothing") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = TrainingRepo.duckdb(conn)
          rows <- repo.streamQuiet.runCollect
        yield assertTrue(rows.isEmpty)
      }
    },
    test("appendBatch with empty Chunk is a no-op") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = TrainingRepo.duckdb(conn)
          _    <- repo.appendBatch(Chunk.empty)
          n    <- repo.count
        yield assertTrue(n == 0L)
      }
    },
  )

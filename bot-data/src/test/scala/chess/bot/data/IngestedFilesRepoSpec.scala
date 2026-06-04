package chess.bot.data

import zio.*
import zio.test.*

object IngestedFilesRepoSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")

  def spec = suite("IngestedFilesRepo")(
    test("isIngested returns false for an unknown path") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = IngestedFilesRepo.duckdb(conn)
          got  <- repo.isIngested("/tmp/never-seen.pgn")
        yield assertTrue(!got)
      }
    },
    test("markIngested + isIngested round-trips a path") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = IngestedFilesRepo.duckdb(conn)
          _    <- repo.markIngested("/corpus/a.pgn", 1234L)
          got  <- repo.isIngested("/corpus/a.pgn")
          others <- repo.isIngested("/corpus/b.pgn")
        yield assertTrue(got, !others)
      }
    },
    test("markIngested is idempotent — re-marking is a no-op (ON CONFLICT DO NOTHING)") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = IngestedFilesRepo.duckdb(conn)
          _    <- repo.markIngested("/corpus/a.pgn", 100L)
          // Same path again with different counts — should not duplicate
          // the row, should not change anything.
          _    <- repo.markIngested("/corpus/a.pgn", 999L)
          listed <- repo.listIngested
        yield assertTrue(listed == List("/corpus/a.pgn"))
      }
    },
    test("listIngested returns every marked path in sorted order") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = IngestedFilesRepo.duckdb(conn)
          _    <- repo.markIngested("/corpus/c.pgn", 1L)
          _    <- repo.markIngested("/corpus/a.pgn", 1L)
          _    <- repo.markIngested("/corpus/b.pgn", 1L)
          listed <- repo.listIngested
        yield assertTrue(
          listed == List("/corpus/a.pgn", "/corpus/b.pgn", "/corpus/c.pgn")
        )
      }
    },
    test("listIngested returns Nil on a fresh DB") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = IngestedFilesRepo.duckdb(conn)
          listed <- repo.listIngested
        yield assertTrue(listed.isEmpty)
      }
    },
  )

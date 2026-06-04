package chess.bot.data

import zio.*
import zio.test.*

object BookRepoSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")

  def spec = suite("BookRepo")(
    test("lookup returns Nil for an unknown position") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = BookRepo.duckdb(conn)
          rows <- repo.lookup(0xdeadbeefL)
        yield assertTrue(rows.isEmpty)
      }
    },
    test("upsert + lookup round-trips a single (position, move) row") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = BookRepo.duckdb(conn)
          row   = BookRow(123L, "e2e4", wins = 5, draws = 2, losses = 1, sumElo = 24000)
          _    <- repo.upsert(Chunk(row))
          rows <- repo.lookup(123L)
        yield assertTrue(
          rows.size == 1,
          rows.head.zobrist == 123L,
          rows.head.moveUci == "e2e4",
          rows.head.wins    == 5L,
          rows.head.draws   == 2L,
          rows.head.losses  == 1L,
          rows.head.sumElo  == 24000L,
        )
      }
    },
    test("upsert accumulates counters on conflict") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = BookRepo.duckdb(conn)
          _    <- repo.upsert(Chunk(
                    BookRow(7L, "d2d4", wins = 3, draws = 0, losses = 1, sumElo = 8000),
                  ))
          _    <- repo.upsert(Chunk(
                    BookRow(7L, "d2d4", wins = 1, draws = 2, losses = 0, sumElo = 4000),
                  ))
          rows <- repo.lookup(7L)
        yield assertTrue(
          rows.size == 1,
          rows.head.wins   == 4L,
          rows.head.draws  == 2L,
          rows.head.losses == 1L,
          rows.head.sumElo == 12000L,
        )
      }
    },
    test("bestMove returns None when no moves are recorded") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = BookRepo.duckdb(conn)
          best <- repo.bestMove(999L)
        yield assertTrue(best.isEmpty)
      }
    },
    test("bestMove ranks by weighted score (wins dominate)") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = BookRepo.duckdb(conn)
          _    <- repo.upsert(Chunk(
                    BookRow(1L, "popular_loss", wins = 0, draws = 0, losses = 50, sumElo = 100000),
                    BookRow(1L, "rare_win",     wins = 4, draws = 0, losses = 0, sumElo = 8000),
                  ))
          best <- repo.bestMove(1L)
        yield assertTrue(best.contains("rare_win"))
      }
    },
    test("bestMove breaks ties by aggregate Elo") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = BookRepo.duckdb(conn)
          _    <- repo.upsert(Chunk(
                    BookRow(2L, "low_elo_3w",  wins = 3, draws = 0, losses = 0, sumElo = 5000),
                    BookRow(2L, "high_elo_3w", wins = 3, draws = 0, losses = 0, sumElo = 9000),
                  ))
          best <- repo.bestMove(2L)
        yield assertTrue(best.contains("high_elo_3w"))
      }
    },
    test("upsert with empty Chunk is a no-op") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = BookRepo.duckdb(conn)
          _    <- repo.upsert(Chunk.empty)
          rows <- repo.lookup(1L)
        yield assertTrue(rows.isEmpty)
      }
    },
  )

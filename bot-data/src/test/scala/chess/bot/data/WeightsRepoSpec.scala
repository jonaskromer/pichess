package chess.bot.data

import zio.*
import zio.test.*

object WeightsRepoSpec extends ZIOSpecDefault:

  private val memoryCfg = Db.Config(path = ":memory:")

  def spec = suite("WeightsRepo")(
    test("latest returns None on a fresh table") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = WeightsRepo.duckdb(conn)
          snap <- repo.latest
        yield assertTrue(snap.isEmpty)
      }
    },
    test("save + latest round-trips a single snapshot") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = WeightsRepo.duckdb(conn)
          snap  = WeightSnapshot(
                    version = 1,
                    weights = Map("pawn" -> 100, "knight" -> 320, "bishop" -> 330),
                  )
          _    <- repo.save(snap)
          out  <- repo.latest
        yield assertTrue(
          out.contains(snap),
          out.exists(_.weights("pawn")  == 100),
          out.exists(_.weights("bishop") == 330),
        )
      }
    },
    test("latest returns the snapshot with the highest version") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = WeightsRepo.duckdb(conn)
          _    <- repo.save(WeightSnapshot(1, Map("pawn" -> 100)))
          _    <- repo.save(WeightSnapshot(2, Map("pawn" -> 105)))
          _    <- repo.save(WeightSnapshot(3, Map("pawn" -> 110)))
          out  <- repo.latest
        yield assertTrue(
          out.exists(_.version == 3),
          out.exists(_.weights("pawn") == 110),
        )
      }
    },
    test("load returns a specific version regardless of recency") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = WeightsRepo.duckdb(conn)
          _    <- repo.save(WeightSnapshot(1, Map("pawn" -> 100)))
          _    <- repo.save(WeightSnapshot(2, Map("pawn" -> 110)))
          v1   <- repo.load(1)
          v2   <- repo.load(2)
        yield assertTrue(
          v1.exists(_.weights("pawn") == 100),
          v2.exists(_.weights("pawn") == 110),
        )
      }
    },
    test("load returns None for an unknown version") {
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = WeightsRepo.duckdb(conn)
          _    <- repo.save(WeightSnapshot(1, Map("pawn" -> 100)))
          out  <- repo.load(99)
        yield assertTrue(out.isEmpty)
      }
    },
    test("save with empty weights map writes nothing") {
      // The repo's save does a batchInsert over the map entries; an
      // empty map results in no rows. `load` on that version then
      // returns None because the row set is empty.
      ZIO.scoped {
        for
          conn <- Db.open(memoryCfg)
          repo  = WeightsRepo.duckdb(conn)
          _    <- repo.save(WeightSnapshot(7, Map.empty))
          out  <- repo.load(7)
        yield assertTrue(out.isEmpty)
      }
    },
  )

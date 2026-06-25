package chess.persistence

import zio.*
import zio.test.*

import chess.model.{GameArchive, GameId}

object InMemoryGameArchiveRepositorySpec extends ZIOSpecDefault:

  private val mkRepo: UIO[GameArchiveRepository] =
    Ref.make(Map.empty[GameId, GameArchive]).map(InMemoryGameArchiveRepository(_))

  private def archive(id: String, result: String): GameArchive =
    GameArchive(id, "local", "White", "Black", result, None, "start", Nil, None, "(no moves)", "(no moves)", "*", 0L)

  def spec = suite("InMemoryGameArchiveRepository")(
    test("save then find returns the archive; save is idempotent (last wins)") {
      for
        repo <- mkRepo
        _    <- repo.save(archive("g", "1-0"))
        _    <- repo.save(archive("g", "0-1")) // overwrite
        got  <- repo.find("g")
        miss <- repo.find("nope")
      yield assertTrue(got.map(_.result) == Some("0-1"), miss == None)
    },
    test("archives are isolated per game id") {
      for
        repo <- mkRepo
        _    <- repo.save(archive("a", "1-0"))
        a    <- repo.find("a")
        b    <- repo.find("b")
      yield assertTrue(a.isDefined, b == None)
    },
    test("layer provides a working repository") {
      (for
        repo <- ZIO.service[GameArchiveRepository]
        _    <- repo.save(archive("g", "1/2-1/2"))
        got  <- repo.find("g")
      yield assertTrue(got.isDefined)).provide(InMemoryGameArchiveRepository.layer)
    }
  )

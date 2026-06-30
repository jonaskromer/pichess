package chess.persistence

import zio.*
import zio.test.*

import chess.model.{TournamentArchive, TournamentStanding}

object InMemoryTournamentArchiveRepositorySpec extends ZIOSpecDefault:

  private def archive(id: String, finishedAt: Long): TournamentArchive =
    TournamentArchive(
      tournamentId = id,
      name = s"name-$id",
      format = "swiss",
      finishedAt = finishedAt,
      standings = List(
        TournamentStanding(
          1, "a", "Alice", Some("piChess"), Some("NNUE+HCE hybrid"),
          Some("weights-v8+nnue-v1"), 2.0, 2, 0, 0, 4.0
        )
      ),
      gameIds = List(s"$id-g1", s"$id-g2")
    )

  def spec = suite("InMemoryTournamentArchiveRepository")(
    test("save+find round-trips, list returns all, save is idempotent by id") {
      (for
        repo    <- ZIO.service[TournamentArchiveRepository]
        _       <- repo.save(archive("t1", 100))
        _       <- repo.save(archive("t2", 200))
        _       <- repo.save(archive("t1", 150)) // overwrite t1 (same id)
        found   <- repo.find("t1")
        missing <- repo.find("nope")
        all     <- repo.list
      yield assertTrue(
        found.exists(_.finishedAt == 150), // last write wins
        found.exists(_.standings.head.engineType.contains("NNUE+HCE hybrid")),
        missing.isEmpty,
        all.map(_.tournamentId).toSet == Set("t1", "t2"),
        all.size == 2 // idempotent: t1 saved twice → one entry
      )).provide(InMemoryTournamentArchiveRepository.layer)
    }
  )

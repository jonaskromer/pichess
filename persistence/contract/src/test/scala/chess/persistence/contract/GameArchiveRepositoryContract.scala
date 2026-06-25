package chess.persistence.contract

import zio.*
import zio.test.*

import chess.model.{ArchivePly, GameArchive}
import chess.persistence.GameArchiveRepository

/** Contract every `GameArchiveRepository` impl must satisfy. Subclasses provide
  * a Testcontainers-backed `repoLayer`; the suite asserts save/find round-trips
  * a full [[GameArchive]] (incl. nested plies) faithfully and that save is
  * idempotent by gameId.
  */
abstract class GameArchiveRepositoryContract extends ZIOSpecDefault:

  def repoLayer: ZLayer[Any, Throwable, GameArchiveRepository]
  def label: String

  private def archive(id: String, result: String): GameArchive =
    GameArchive(
      gameId = id,
      source = "local",
      white = "White",
      black = "Black",
      result = result,
      timeControl = Some("180+2"),
      initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      plies = List(
        ArchivePly(0, "white", "e4", "e2e4", "fen0", 1L, Some(90000L), None),
        ArchivePly(1, "black", "c5", "c7c5", "fen1", 2L, Some(88000L), Some(2000L))
      ),
      openingEco = Some("B20"),
      openingName = "Sicilian Defense",
      openingFamily = "Sicilian",
      pgn = "1. e4 c5 *",
      finishedAt = 5L
    )

  private def save(a: GameArchive) =
    ZIO.serviceWithZIO[GameArchiveRepository](_.save(a))
  private def find(id: String) =
    ZIO.serviceWithZIO[GameArchiveRepository](_.find(id))

  override final def spec =
    suite(s"GameArchiveRepository contract — $label")(
      test("find returns None for an unknown id") {
        for result <- find("does-not-exist")
        yield assertTrue(result.isEmpty)
      },
      test("save then find round-trips the full archive (incl. plies)") {
        val a = archive("contract-a", "1-0")
        for
          _      <- save(a)
          result <- find("contract-a")
        yield assertTrue(result.contains(a))
      },
      test("save is idempotent (last write wins)") {
        for
          _      <- save(archive("contract-b", "1-0"))
          _      <- save(archive("contract-b", "0-1"))
          result <- find("contract-b")
        yield assertTrue(result.map(_.result) == Some("0-1"))
      },
      test("ids are isolated") {
        for
          _       <- save(archive("contract-c", "1/2-1/2"))
          missing <- find("contract-c-other")
        yield assertTrue(missing.isEmpty)
      }
    ).provideShared(repoLayer) @@ TestAspect.withLiveClock

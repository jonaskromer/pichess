package chess.repository

import zio.test.*

import chess.opening.{EcoBook, EcoEntry}
import chess.repository.api.{ArchiveSubmissionDto, SubmittedMoveDto}

object TournamentArchiveSpec extends ZIOSpecDefault:

  private val eco = EcoBook.fromEntries(
    Vector(EcoEntry("B20", "Sicilian Defense", List("e4", "c5")))
  )

  def spec = suite("TournamentArchive")(
    test("builds an archive from a UCI submission (SAN, opening, clocks, names)") {
      val dto = ArchiveSubmissionDto(
        gameId = "t1",
        source = "tournament",
        white = "piChess",
        black = "Rival",
        result = "1-0",
        timeControl = Some("180+2"),
        moves = List(
          SubmittedMoveDto("e2e4", Some(180000L), None),
          SubmittedMoveDto("c7c5", Some(178000L), Some(2000L))
        )
      )
      for a <- TournamentArchive.fromSubmission(dto, eco)
      yield assertTrue(
        a.plies.map(_.san) == List("e4", "c5"),
        a.plies.head.uci == "e2e4",
        a.plies.head.clockMs == Some(180000L),
        a.white == "piChess",
        a.black == "Rival",
        a.source == "tournament",
        a.openingName == "Sicilian Defense",
        a.result == "1-0",
        a.pgn.contains("[White \"piChess\"]"),
        a.pgn.contains("[%clk 0:03:00]")
      )
    },
    test("rejects a malformed UCI move") {
      val dto = ArchiveSubmissionDto(
        "t2", "tournament", "a", "b", "*", None,
        List(SubmittedMoveDto("zzzz", None, None))
      )
      for r <- TournamentArchive.fromSubmission(dto, eco).either
      yield assertTrue(r.isLeft)
    }
  )

package chess.model

import zio.test.*

object GameArchiveSpec extends ZIOSpecDefault:

  def spec = suite("GameArchive")(
    test("holds plies and finalized metadata") {
      val ply = ArchivePly(
        ply = 0,
        color = "white",
        san = "e4",
        uci = "e2e4",
        fenAfter = "fen",
        occurredAt = 1L,
        clockMs = Some(90000L),
        emtMs = None
      )
      val archive = GameArchive(
        gameId = "g1",
        source = "local",
        white = "White",
        black = "Black",
        result = "1-0",
        timeControl = Some("180+2"),
        initialFen = "start",
        plies = List(ply),
        openingEco = Some("B20"),
        openingName = "Sicilian Defense",
        openingFamily = "Sicilian",
        pgn = "1. e4 1-0",
        finishedAt = 2L
      )
      assertTrue(
        archive.plies.head.clockMs == Some(90000L),
        archive.openingFamily == "Sicilian",
        archive.result == "1-0"
      )
    }
  )

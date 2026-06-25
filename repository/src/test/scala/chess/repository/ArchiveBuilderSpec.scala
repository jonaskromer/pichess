package chess.repository

import zio.test.*

import chess.model.ArchivePly
import chess.opening.{EcoBook, EcoEntry}

object ArchiveBuilderSpec extends ZIOSpecDefault:

  private val eco = EcoBook.fromEntries(
    Vector(EcoEntry("B20", "Sicilian Defense", List("e4", "c5")))
  )

  private def ply(idx: Int, color: String, san: String, clk: Option[Long]): ArchivePly =
    ArchivePly(idx, color, san, san, "fen", idx.toLong, clk, None)

  def spec = suite("ArchiveBuilder")(
    test("identifies the opening and serializes PGN with clocks + headers") {
      val plies = List(
        ply(1, "black", "c5", Some(88000)), // out of order on purpose
        ply(0, "white", "e4", Some(90000))
      )
      for archive <- ArchiveBuilder.build(
          "g1",
          "tournament",
          "piChess",
          "Opponent",
          plies,
          "1-0",
          5L,
          eco,
          Some("180+2")
        )
      yield assertTrue(
        archive.white == "piChess",
        archive.black == "Opponent",
        archive.pgn.contains("[White \"piChess\"]"),
        archive.openingEco == Some("B20"),
        archive.openingName == "Sicilian Defense",
        archive.openingFamily == "Sicilian",
        archive.plies.map(_.ply) == List(0, 1), // sorted
        archive.result == "1-0",
        archive.timeControl == Some("180+2"),
        archive.pgn.contains("[ECO \"B20\"]"),
        archive.pgn.contains("[Opening \"Sicilian Defense\"]"),
        archive.pgn.contains("[TimeControl \"180+2\"]"),
        archive.pgn.contains("1. e4 {[%clk 0:01:30]}"),
        archive.pgn.endsWith("1-0")
      )
    },
    test("an empty game has no ECO header and the (no moves) opening") {
      for archive <- ArchiveBuilder.build("g2", "local", "White", "Black", Nil, "*", 0L, eco, None)
      yield assertTrue(
        archive.openingEco == None,
        archive.openingName == "(no moves)",
        archive.plies.isEmpty,
        !archive.pgn.contains("[ECO"),
        !archive.pgn.contains("[TimeControl")
      )
    }
  )

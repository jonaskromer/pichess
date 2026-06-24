package chess.bot.tournament

import zio.test.*

/** Pins the pure UCI move-log helpers [[Openings]] uses to derive opening
  * family, first move, and length at game end.
  */
object OpeningsSpec extends ZIOSpecDefault:

  def spec = suite("Openings")(
    suite("append")(
      test("seeds an empty log, then space-joins") {
        assertTrue(
          Openings.append("", "e2e4") == "e2e4",
          Openings.append("e2e4", "e7e5") == "e2e4 e7e5"
        )
      }
    ),
    suite("firstMove / plies")(
      test("first token and count, empty → None / 0") {
        assertTrue(
          Openings.firstMove("e2e4 e7e5 g1f3") == Some("e2e4"),
          Openings.firstMove("") == None,
          Openings.plies("e2e4 e7e5 g1f3") == 3,
          Openings.plies("") == 0
        )
      }
    ),
    suite("family")(
      test("most-specific prefix wins over the generic one") {
        assertTrue(
          Openings.family("e2e4 e7e5 g1f3 b8c6 f1b5") == "Ruy Lopez",
          Openings.family("e2e4 e7e5 g1f3 b8c6 f1c4") == "Italian",
          Openings.family("e2e4 e7e5 g1f3 g8f6") == "Open Game",
          Openings.family("e2e4 e7e5") == "Open Game"
        )
      },
      test("first-move and two-move families") {
        assertTrue(
          Openings.family("e2e4 c7c5") == "Sicilian",
          Openings.family("d2d4 d7d5 c2c4") == "Queen's Gambit",
          Openings.family("c2c4") == "English",
          Openings.family("g1f3") == "Réti",
          Openings.family("e2e4") == "King's Pawn",
          Openings.family("d2d4") == "Queen's Pawn"
        )
      },
      test("unknown → Other; empty → (no moves)") {
        assertTrue(
          Openings.family("g2g4 f2f3") == "Other",
          Openings.family("") == "(no moves)",
          Openings.family("   ") == "(no moves)"
        )
      }
    )
  )

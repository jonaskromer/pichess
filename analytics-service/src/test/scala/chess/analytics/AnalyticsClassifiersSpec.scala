package chess.analytics

import zio.test.*

import chess.api.AnalyticsSummaryDto

object AnalyticsClassifiersSpec extends ZIOSpecDefault:

  def spec = suite("analytics classifiers")(
    suite("MoveFeatures")(
      test("capture / check / mate / promotion") {
        assertTrue(
          MoveFeatures.isCapture("exd5"),
          !MoveFeatures.isCapture("e4"),
          MoveFeatures.isCheck("Qh5+"),
          MoveFeatures.isCheckmate("Qh7#"),
          !MoveFeatures.isCheck("e4"),
          MoveFeatures.isPromotion("e8=Q"),
          !MoveFeatures.isPromotion("e7")
        )
      },
      test("castling sides") {
        assertTrue(
          MoveFeatures.isKingsideCastle("O-O"),
          !MoveFeatures.isKingsideCastle("O-O-O"),
          MoveFeatures.isQueensideCastle("O-O-O"),
          !MoveFeatures.isQueensideCastle("O-O")
        )
      }
    ),
    suite("Eco.familyOf")(
      test("specific prefix beats generic") {
        assertTrue(
          Eco.familyOf("e4 e5 Nf3 Nc6 Bb5 a6") == "Ruy Lopez",
          Eco.familyOf("e4 c5 Nf3 d6") == "Sicilian",
          Eco.familyOf("d4 d5 c4 e6") == "Queen's Gambit",
          Eco.familyOf("e4") == "King's Pawn",
          Eco.familyOf("c4 e5") == "English"
        )
      },
      test("empty and unknown") {
        assertTrue(
          Eco.familyOf("") == "(no moves)",
          Eco.familyOf("g4 f3") == "Other"
        )
      }
    ),
    suite("Outcomes.classify")(
      test("forfeit → winning colour + resignation") {
        assertTrue(Outcomes.classify("Forfeited", "White") == ("White", "resignation"))
      },
      test("draw claim → Draw + draw-claim") {
        assertTrue(Outcomes.classify("DrawClaimed", "") == ("Draw", "draw-claim"))
      },
      test("forfeit with no winner detail → Decisive") {
        assertTrue(Outcomes.classify("Forfeited", "") == ("Decisive", "resignation"))
      },
      test("GameEnded decisive vs drawish vs empty") {
        assertTrue(
          Outcomes.classify("GameEnded", "Checkmate") == ("Decisive", "Checkmate"),
          Outcomes.classify("GameEnded", "Stalemate") == ("Draw", "Stalemate"),
          Outcomes.classify("GameEnded", "") == ("Decisive", "other")
        )
      },
      test("unknown terminal type falls back (with and without detail)") {
        assertTrue(
          Outcomes.classify("Weird", "") == ("Unknown", "Weird"),
          Outcomes.classify("Weird", "x") == ("Unknown", "x")
        )
      }
    ),
    suite("Records.fold")(
      test("tracks longest, shortest and most captures") {
        def s(moves: Int, caps: Int): AnalyticsSummaryDto =
          AnalyticsSummaryDto("g", moves, caps, 0L, "e4", "GameEnded", "Draw", 0.0)
        val r = List(s(20, 3), s(8, 7), s(40, 2))
          .foldLeft(Records.empty)(Records.fold)
        assertTrue(
          r.longestGameMoves == 40,
          r.shortestGameMoves == 8,
          r.mostCaptures == 7
        )
      }
    )
  )

package chess.bot.tournament

import zio.test.*

import chess.model.piece.Color

/** Pins [[GameOutcome.classify]] across the win/loss/draw cases and the status
  * normalisation the dashboard's `result`/`status` split relies on.
  */
object GameOutcomeSpec extends ZIOSpecDefault:

  def spec = suite("GameOutcome.classify")(
    test("we win when the winning colour is ours") {
      assertTrue(
        GameOutcome.classify(Some(Color.White), "checkmate", Color.White) ==
          GameOutcome.Outcome("win", "checkmate")
      )
    },
    test("we lose when the winning colour is the opponent's") {
      assertTrue(
        GameOutcome.classify(Some(Color.White), "resigned", Color.Black) ==
          GameOutcome.Outcome("loss", "resigned")
      )
    },
    test("no winner → draw") {
      assertTrue(
        GameOutcome.classify(None, "stalemate", Color.White) ==
          GameOutcome.Outcome("draw", "stalemate")
      )
    },
    test("status is lowercased; blank becomes 'unknown'") {
      assertTrue(
        GameOutcome.classify(Some(Color.Black), "TIMEOUT", Color.Black) ==
          GameOutcome.Outcome("win", "timeout"),
        GameOutcome.classify(None, "   ", Color.White) ==
          GameOutcome.Outcome("draw", "unknown")
      )
    }
  )

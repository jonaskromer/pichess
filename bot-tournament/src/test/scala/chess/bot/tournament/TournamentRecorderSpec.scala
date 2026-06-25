package chess.bot.tournament

import zio.test.*

import chess.model.piece.Color
import chess.repository.api.SubmittedMoveDto

object TournamentRecorderSpec extends ZIOSpecDefault:

  private val moves = Vector(SubmittedMoveDto("e2e4", Some(120000L), None))

  def spec = suite("TournamentRecorder")(
    test("submission resolves white/black by our colour and result by winner") {
      val asWhite = TournamentRecorder.submission(
        "g1", "piChess", Color.White, "Rival", Some(Color.White), moves
      )
      val asBlack = TournamentRecorder.submission(
        "g2", "piChess", Color.Black, "Rival", Some(Color.White), moves
      )
      assertTrue(
        asWhite.white == "piChess",
        asWhite.black == "Rival",
        asWhite.result == "1-0",
        asWhite.source == "tournament",
        asWhite.moves.map(_.uci) == List("e2e4"),
        asBlack.white == "Rival",
        asBlack.black == "piChess",
        asBlack.result == "1-0"
      )
    },
    test("result token: draw (no winner) and black win") {
      assertTrue(
        TournamentRecorder
          .submission("g", "b", Color.White, "o", None, moves)
          .result == "1/2-1/2",
        TournamentRecorder
          .submission("g", "b", Color.White, "o", Some(Color.Black), moves)
          .result == "0-1"
      )
    },
    test("moverClockMs: the side that just moved (opposite of `turn`), clamped") {
      val clock = GameClock(whiteTime = 100.0, blackTime = 90.0)
      assertTrue(
        TournamentRecorder.moverClockMs(clock, Color.Black) == 100000L, // white moved
        TournamentRecorder.moverClockMs(clock, Color.White) == 90000L,  // black moved
        TournamentRecorder.moverClockMs(GameClock(-5.0, 1.0), Color.Black) == 0L
      )
    }
  )

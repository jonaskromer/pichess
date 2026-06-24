package chess.bot.tournament

import zio.test.*

import chess.model.piece.Color

/** Pins [[TournamentRunner.decide]]'s output for representative events. The
  * bridge plays entirely off these decisions.
  */
object TournamentRunnerSpec extends ZIOSpecDefault:

  private val whiteToMove =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val blackToMove =
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
  private val clock = GameClock(whiteTime = 10.0, blackTime = 20.0)

  def spec = suite("TournamentRunner.decide")(
    suite("gameState snapshot")(
      test("ongoing + our turn (white) → MoveFrom with our/opp clocks") {
        val ev = GameEvent.StateSnapshot(
          whiteToMove,
          "",
          Color.White,
          clock,
          "ongoing",
          None
        )
        TournamentRunner.decide(ev, Color.White) match
          case TournamentRunner.Action.MoveFrom(s, ourSec, oppSec) =>
            assertTrue(
              s.activeColor == Color.White,
              ourSec == 10.0,
              oppSec == 20.0
            )
          case other => assertTrue(false, other.toString.nonEmpty)
      },
      test("ongoing + not our turn → None") {
        val ev = GameEvent
          .StateSnapshot(whiteToMove, "", Color.White, clock, "ongoing", None)
        assertTrue(
          TournamentRunner
            .decide(ev, Color.Black) == TournamentRunner.Action.None
        )
      },
      test("pending (queued behind maxConcurrentGames) → None (wait)") {
        val ev = GameEvent
          .StateSnapshot(whiteToMove, "", Color.White, clock, "pending", None)
        assertTrue(
          TournamentRunner
            .decide(ev, Color.White) == TournamentRunner.Action.None
        )
      },
      test("terminal status (checkmate) → GameOver") {
        val ev = GameEvent.StateSnapshot(
          whiteToMove,
          "",
          Color.White,
          clock,
          "checkmate",
          Some(Color.Black)
        )
        assertTrue(
          TournamentRunner
            .decide(ev, Color.White) == TournamentRunner.Action.GameOver
        )
      },
      test("malformed FEN on our turn → MalformedEvent (no crash)") {
        val ev = GameEvent
          .StateSnapshot("not a fen", "", Color.White, clock, "ongoing", None)
        assertTrue(
          TournamentRunner.decide(ev, Color.White) match
            case TournamentRunner.Action.MalformedEvent(_) => true
            case _                                         => false
        )
      }
    ),
    suite("move")(
      test("our turn (black) → MoveFrom with swapped clocks (ours=black)") {
        val ev = GameEvent.MovePlayed("e2e4", blackToMove, Color.Black, clock)
        TournamentRunner.decide(ev, Color.Black) match
          case TournamentRunner.Action.MoveFrom(s, ourSec, oppSec) =>
            assertTrue(
              s.activeColor == Color.Black,
              ourSec == 20.0,
              oppSec == 10.0
            )
          case other => assertTrue(false, other.toString.nonEmpty)
      },
      test("opponent's move (not our turn) → None") {
        val ev = GameEvent.MovePlayed("e2e4", blackToMove, Color.Black, clock)
        assertTrue(
          TournamentRunner
            .decide(ev, Color.White) == TournamentRunner.Action.None
        )
      }
    ),
    suite("gameEnd")(
      test("→ GameOver") {
        val ev = GameEvent.GameEnded(None, "draw")
        assertTrue(
          TournamentRunner.decide(
            ev,
            Color.White
          ) == TournamentRunner.Action.GameOver
        )
      }
    ),
    suite("heartbeat")(
      test("→ None (keep-alive, ignored)") {
        assertTrue(
          TournamentRunner
            .decide(
              GameEvent.Heartbeat,
              Color.White
            ) == TournamentRunner.Action.None
        )
      }
    )
  )

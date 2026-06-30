package chess.controller

import zio.*
import zio.test.*

/** Regression guard for the tournament-spectate move-replay index
  * ([[TournamentSpectate.replayPending]]).
  *
  * The mirror follows the tournament server's cumulative move list and replays
  * the not-yet-applied tail onto the game-service mirror. The bug this pins: if
  * a `makeMove` replay FAILS (observed live when a heavy bot think CPU-starved
  * the mirror's makeMove), the applied index must NOT advance past the failed
  * move — otherwise that move is skipped forever and the mirror board desyncs
  * permanently, so every later move is illegal (cascade of "No piece at X"
  * rejections → spectators see a frozen/wrong board).
  *
  * Pure (a stub `apply`), so no gRPC/game-service needed.
  */
object TournamentSpectateReplaySpec extends ZIOSpecDefault:

  /** A stub `apply` recording every UCI it is handed, failing on `failOn`. */
  private def recordingApply(
      failOn: Set[String]
  ): UIO[(Ref[Vector[String]], String => IO[String, Unit])] =
    Ref.make(Vector.empty[String]).map { calls =>
      (
        calls,
        (uci: String) =>
          calls.update(_ :+ uci) *>
            ZIO.fail(s"reject $uci").when(failOn(uci)).unit
      )
    }

  private val ignoreReject: (String, String) => UIO[Unit] =
    (_, _) => ZIO.unit

  def spec = suite("TournamentSpectate.replayPending")(
    test("happy: all pending moves apply → index advances to the full count") {
      for
        sa <- recordingApply(Set.empty)
        (calls, apply) = sa
        n <- TournamentSpectate.replayPending(
          0,
          Vector("a", "b", "c"),
          apply,
          ignoreReject
        )
        applied <- calls.get
      yield assertTrue(n == 3, applied == Vector("a", "b", "c"))
    },
    test("only the moves past the already-applied index are replayed") {
      for
        sa <- recordingApply(Set.empty)
        (calls, apply) = sa
        n <- TournamentSpectate.replayPending(
          2,
          Vector("a", "b", "c", "d"),
          apply,
          ignoreReject
        )
        applied <- calls.get
        // a, b were already applied (index 2) → only c, d replayed.
      yield assertTrue(n == 4, applied == Vector("c", "d"))
    },
    test("unhappy: a rejected move stops replay WITHOUT skipping it") {
      for
        sa <- recordingApply(Set("b"))
        (calls, apply) = sa
        rejected <- Ref.make(Vector.empty[String])
        n <- TournamentSpectate.replayPending(
          0,
          Vector("a", "b", "c"),
          apply,
          (uci, _) => rejected.update(_ :+ uci)
        )
        applied <- calls.get
        rej     <- rejected.get
      yield assertTrue(
        n == 1, // advanced past 'a' only — 'b' is NOT skipped
        applied == Vector("a", "b"), // tried a (ok) then b (failed); never c
        rej == Vector("b")
      )
    },
    test("recovery: once a transient failure clears, the next poll resumes") {
      for
        flaky <- Ref.make(true) // 'b' fails while true (a transient CPU starve)
        calls <- Ref.make(Vector.empty[String])
        apply = (uci: String) =>
          calls.update(_ :+ uci) *> {
            if uci == "b" then
              flaky.get.flatMap(f => ZIO.fail("transient b").when(f).unit)
            else ZIO.unit
          }
        n1 <- TournamentSpectate.replayPending(
          0,
          Vector("a", "b", "c"),
          apply,
          ignoreReject
        )
        _  <- flaky.set(false) // starvation cleared
        n2 <- TournamentSpectate.replayPending(
          n1,
          Vector("a", "b", "c"),
          apply,
          ignoreReject
        )
        applied <- calls.get
      yield assertTrue(
        n1 == 1, // stuck at 'b' on the first poll
        n2 == 3, // recovered on the next poll
        applied == Vector("a", "b", "b", "c") // a, b(fail) | b(ok), c
      )
    }
  )

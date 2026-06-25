package chess.model

import zio.test.*

import chess.model.piece.Color

/** Pure clock arithmetic — the authoritative core the server flags on. Every
  * transition takes an explicit `now`, so these are fully deterministic.
  */
object ClockStateSpec extends ZIOSpecDefault:

  // 5+2: five minutes each, two-second increment, White running since t=1000.
  private def c(
      whiteMs: Long = 300000,
      blackMs: Long = 300000,
      incMs: Long = 2000,
      since: Option[Long] = Some(1000)
  ): ClockState = ClockState(whiteMs, blackMs, incMs, since)

  def spec = suite("ClockState")(
    test("initial: both sides at the limit, side-to-move running from now") {
      val k = ClockState.initial(300000, 2000, now = 5000)
      assertTrue(
        k.whiteMs == 300000L,
        k.blackMs == 300000L,
        k.incrementMs == 2000L,
        k.runningSince.contains(5000L)
      )
    },
    test("bankedFor reads the per-colour banked time") {
      val k = c(whiteMs = 111, blackMs = 222)
      assertTrue(
        k.bankedFor(Color.White) == 111L,
        k.bankedFor(Color.Black) == 222L
      )
    },
    suite("liveRemaining")(
      test("the running side ticks down from its banked time") {
        // White running since 1000; at 4000, 3000ms have elapsed.
        assertTrue(
          c(whiteMs = 300000)
            .liveRemaining(Color.White, Color.White, 4000) == 297000L
        )
      },
      test("never drops below zero") {
        assertTrue(
          c(whiteMs = 500).liveRemaining(Color.White, Color.White, 10000) == 0L
        )
      },
      test("the side NOT to move reads its banked time unchanged") {
        assertTrue(
          c(blackMs = 300000)
            .liveRemaining(Color.Black, Color.White, 9999) == 300000L
        )
      },
      test("a paused clock reads banked for either side") {
        val k = c(whiteMs = 1234, since = None)
        assertTrue(
          k.liveRemaining(Color.White, Color.White, 9999) == 1234L,
          k.liveRemaining(Color.Black, Color.White, 9999) == k.blackMs
        )
      }
    ),
    suite("afterMove")(
      test(
        "banks the mover's elapsed time, adds the increment, starts the opponent"
      ) {
        // White running since 1000; moves at 4000 (3000ms used), +2000 increment.
        val k = c(whiteMs = 300000).afterMove(Color.White, now = 4000)
        assertTrue(
          k.whiteMs == 299000L, // 300000 - 3000 + 2000
          k.blackMs == 300000L,
          k.runningSince.contains(4000L)
        )
      },
      test(
        "from a paused clock, no time is banked off (only the increment added)"
      ) {
        val k =
          c(whiteMs = 300000, since = None).afterMove(Color.White, now = 4000)
        assertTrue(k.whiteMs == 302000L, k.runningSince.contains(4000L))
      },
      test("clamps the mover's clock at zero on a long think") {
        val k = c(whiteMs = 1000).afterMove(Color.White, now = 99999)
        assertTrue(k.whiteMs == 0L)
      },
      test("black moving decrements black") {
        val k = c(blackMs = 300000, since = Some(2000))
          .afterMove(Color.Black, now = 5000)
        assertTrue(k.blackMs == 299000L, k.whiteMs == 300000L)
      }
    ),
    suite("stopped")(
      test("banks the running side's elapsed time and pauses") {
        val k = c(whiteMs = 300000).stopped(Color.White, now = 4000)
        assertTrue(k.whiteMs == 297000L, k.runningSince.isEmpty)
      },
      test("an already-paused clock is unchanged") {
        val k = c(since = None)
        assertTrue(k.stopped(Color.White, 9999) == k)
      }
    ),
    suite("flagged")(
      test("true once the running side's time is exhausted") {
        assertTrue(
          c(whiteMs = 1000).flagged(Color.White, now = 2000)
        ) // 1000 - 1000 <= 0
      },
      test("false while time remains") {
        assertTrue(!c(whiteMs = 300000).flagged(Color.White, now = 2000))
      },
      test("a paused clock never flags") {
        assertTrue(
          !c(whiteMs = 0, since = None).flagged(Color.White, now = 9999)
        )
      }
    )
  )

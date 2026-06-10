package chess.model.rules

import zio.*
import zio.test.*

/** Drives [[BitboardAttacks.searchMagic]] directly.
  *
  * The production magic tables are built from it at class-load (and the
  * success path is therefore exercised by every other rules test that
  * touches the attack tables), but the *exhaustion* branch — never
  * finding a collision-free multiplier — is unreachable with real chess
  * masks. We force it here with a zero attempt budget so the typed
  * failure is covered rather than left as dead defensive code.
  */
object BitboardAttacksSpec extends ZIOSpecDefault:

  def spec = suite("BitboardAttacks.searchMagic")(
    test("returns the magic + populated table when a collision-free constant exists") {
      // n = 1 (a 0-bit mask): the sole blocker subset hashes to slot 0
      // regardless of the multiplier, so the first candidate succeeds.
      for result <- BitboardAttacks.searchMagic(
          sq = 0,
          occupancies = Array(0L),
          attacks = Array(42L),
          shift = 63,
          n = 1,
          rng = new java.util.Random(1L),
          maxAttempts = 5,
        )
      yield
        val (_, table) = result
        assertTrue(table.toList == List(42L))
    },
    test("fails with MagicSearchExhausted once the attempt budget is spent") {
      for exit <- BitboardAttacks
          .searchMagic(
            sq = 7,
            occupancies = Array(0L),
            attacks = Array(0L),
            shift = 63,
            n = 1,
            rng = new java.util.Random(1L),
            maxAttempts = 0,
          )
          .exit
      yield assertTrue(
        // compare the failure value (not the whole Exit) to stay
        // independent of the attached stack trace.
        exit.causeOption.flatMap(_.failureOption)
          .contains(BitboardAttacks.MagicSearchExhausted(7, 0))
      )
    },
  )

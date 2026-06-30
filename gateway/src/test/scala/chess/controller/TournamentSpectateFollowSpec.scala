package chess.controller

import zio.test.*

/** Guards the tournament-spectate follower's lifecycle decision
  * ([[TournamentSpectate.followStep]]): keep following while a game is live AND
  * someone is watching, but reap the follower (and its mirror) once the game
  * ends or nobody has watched for the grace window — so an orphaned mirror
  * doesn't keep polling/replaying for ~20 min after the last viewer leaves.
  */
object TournamentSpectateFollowSpec extends ZIOSpecDefault:

  private val Cap = 30 // MaxIdlePolls

  def spec = suite("TournamentSpectate.followStep")(
    test("a terminal game stops the follower regardless of viewers") {
      assertTrue(
        TournamentSpectate.followStep(5, 0, terminal = true, Cap).isEmpty
      )
    },
    test("viewers present → keep polling, idle streak resets to 0") {
      assertTrue(
        TournamentSpectate.followStep(3, 10, terminal = false, Cap).contains(0)
      )
    },
    test("no viewers → idle streak grows, keep polling under the cap") {
      assertTrue(
        TournamentSpectate.followStep(0, 5, terminal = false, Cap).contains(6)
      )
    },
    test("no viewers for the whole grace window → stop") {
      // 29 + 1 == 30 == cap → reap.
      assertTrue(
        TournamentSpectate.followStep(0, 29, terminal = false, Cap).isEmpty
      )
    },
    test("a viewer returning just before the cap resets the streak") {
      assertTrue(
        TournamentSpectate.followStep(1, 29, terminal = false, Cap).contains(0)
      )
    }
  )

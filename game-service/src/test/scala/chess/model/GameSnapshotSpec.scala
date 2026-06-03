package chess.model

import chess.model.board.{GameState, Move, Position}
import chess.model.rules.Zobrist
import zio.test.*

/** Unit tests for the edge cases of [[GameSnapshot]]'s helpers that the
  * integration tests don't naturally exercise.
  */
object GameSnapshotSpec extends ZIOSpecDefault:

  def spec = suite("GameSnapshot")(
    test("replaceHead on an empty history returns the snapshot unchanged") {
      // replaceHead is used to swap the current state's status (e.g. to Draw)
      // on a game that has moves played. Calling it on a fresh snapshot with
      // no history should be a no-op rather than raising.
      val fresh = GameSnapshot.fresh("id", GameState.initial)
      val altered = GameState.initial.copy(inCheck = true)
      val result = fresh.replaceHead(altered)
      assertTrue(result == fresh)
    },
    test(
      "withCurrentState on an empty history updates initialState so state resolves correctly"
    ) {
      // Forfeit at move 0 needs to be representable, so withCurrentState
      // must update the snapshot's resolved current state even when there
      // is no move history yet.
      val fresh = GameSnapshot.fresh("id", GameState.initial)
      val altered = GameState.initial.copy(inCheck = true)
      val updated = fresh.withCurrentState(altered)
      assertTrue(
        updated.state == altered,
        updated.initialState == altered,
        updated.history.isEmpty
      )
    },
    test("withCurrentState on a non-empty history delegates to replaceHead") {
      val pos1 = Position('e', 2)
      val pos2 = Position('e', 4)
      val mv = Move(pos1, pos2)
      val mid =
        GameState.initial.copy(activeColor = chess.model.piece.Color.Black)
      val withMove =
        GameSnapshot.fresh("id", GameState.initial).recordMove(mv, mid, "e4")
      val altered = mid.copy(inCheck = true)
      val updated = withMove.withCurrentState(altered)
      assertTrue(
        updated.state == altered,
        updated.history.head._2 == altered,
        updated.initialState == GameState.initial
      )
    },
    test("countOf returns 0 for a position that has never occurred") {
      val fresh = GameSnapshot.fresh("id", GameState.initial)
      // Construct a state that is provably not the initial — different active
      // color ensures a distinct Zobrist hash.
      val unreached = GameState.initial.copy(
        activeColor = chess.model.piece.Color.Black
      )
      assertTrue(fresh.countOf(unreached) == 0)
    },
    test("countOf returns 1 for the freshly initialized position") {
      val fresh = GameSnapshot.fresh("id", GameState.initial)
      assertTrue(fresh.countOf(GameState.initial) == 1)
    },
    test("undoOnce on a fresh snapshot returns None") {
      val fresh = GameSnapshot.fresh("id", GameState.initial)
      assertTrue(fresh.undoOnce.isEmpty)
    },
    test("redoOnce on a fresh snapshot returns None") {
      val fresh = GameSnapshot.fresh("id", GameState.initial)
      assertTrue(fresh.redoOnce.isEmpty)
    },
    test("fromHistory builds counts consistent with incremental recordMove") {
      // Two ways to construct the same post-sequence snapshot: (a) replay via
      // recordMove, (b) call fromHistory with the full history up front.
      // Both must produce identical positionCounts.
      val move = Move(Position('e', 2), Position('e', 4))
      val after = GameState.initial.copy(
        enPassantTarget = Some(Position('e', 3)),
        activeColor = chess.model.piece.Color.Black
      )
      val viaRecord = GameSnapshot
        .fresh("id", GameState.initial)
        .recordMove(move, after, "e4")
      for viaFromHistory <- GameSnapshot.fromHistory(
                              "id",
                              GameState.initial,
                              List((move, after))
                            )
      yield assertTrue(
        viaRecord.positionCounts == viaFromHistory.positionCounts
      )
    },
    test("fromHistory increments counts when the same position recurs") {
      // The previous test only exercises the "key absent" arm of
      // `updatedWith(...)(_.map(_ + 1).orElse(Some(1)))` — each Zobrist
      // hash is unique. A repeated position (the initial position appears
      // in `allStates` more than once) forces the `_.map(_ + 1)` arm to
      // fire and `positionCounts` to record >1 for that hash.
      //
      // Construct a fake history whose post-move state IS the initial
      // position: the snapshot then sees initial twice (once as
      // `initialState`, once as the post-move state of the synthetic
      // ply), so the hash count for the initial position becomes 2.
      val dummyMove = Move(Position('e', 2), Position('e', 4))
      for snapshot <- GameSnapshot.fromHistory(
                       "id",
                       GameState.initial,
                       List((dummyMove, GameState.initial))
                     )
      yield assertTrue(
        snapshot.positionCounts(Zobrist.hash(GameState.initial)) == 2
      )
    }
  )

package chess.model

import chess.model.board.{GameState, Move, Position}
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
        .recordMove(move, after)
      val viaFromHistory = GameSnapshot.fromHistory(
        "id",
        GameState.initial,
        List((move, after))
      )
      assertTrue(viaRecord.positionCounts == viaFromHistory.positionCounts)
    }
  )

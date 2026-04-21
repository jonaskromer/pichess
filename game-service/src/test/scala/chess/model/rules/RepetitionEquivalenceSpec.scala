package chess.model.rules

import chess.codec.FenSerializer
import chess.notation.MoveParser
import chess.model.{GameError, GameSnapshot}
import chess.model.board.{GameState, Move}
import zio.{IO, ZIO}
import zio.test.*

/** Equivalence test between Zobrist-based and FEN-based repetition counting.
  *
  * [[GameSnapshot.positionCounts]] is an incrementally-maintained,
  * Zobrist-keyed map. Its contract is that it agrees, at every reachable
  * state, with the FEN-string equality class defined by
  * [[FenSerializer.positionKey]] — the original reference implementation.
  *
  * After Phase B's cutover, `GameController.countCurrentPosition` itself uses
  * the Zobrist map, so comparing against it would be a tautology. This spec
  * instead computes the FEN-based count inline via [[fenBasedCount]] so the
  * equivalence check remains a real regression guard even after cutover.
  *
  * For every position reached across forward play, undo, and redo, the two
  * schemes must agree. Any divergence points at an exact state and labels
  * the step where the mismatch occurred.
  */
object RepetitionEquivalenceSpec extends ZIOSpecDefault:

  /** Curated sequences that exercise every position-identity feature: opening
    * and endgame, castling both colors kingside and opposite-side, en passant
    * cycle, promotion, checkmate, and repeated positions via knight shuffle.
    */
  private val corpusSequences: List[List[String]] = List(
    // Ruy Lopez with both kingside castling
    List(
      "e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7", "Re1",
      "b5", "Bb3", "d6", "c3", "O-O"
    ),
    // Najdorf English Attack: opposite-side castling
    List(
      "e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6", "Be3",
      "e5", "Nb3", "Be6", "f3", "b5", "Qd2", "Nbd7", "O-O-O", "Be7", "g4",
      "O-O"
    ),
    // En passant mid-cycle
    List("e4", "Nf6", "e5", "d5", "exd6"),
    // Scholar's mate
    List("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#"),
    // Knight shuffle producing the initial position 3× (threefold territory)
    List(
      "Nf3", "Nc6", "Ng1", "Nb8", "Nf3", "Nc6", "Ng1", "Nb8"
    )
  )

  /** Diagnostic type: a description of where an equivalence mismatch occurred
    * and what the two schemes reported. Returned in a list so the test can
    * report all mismatches rather than halting on the first.
    */
  private type Mismatch = String

  /** FEN-based reference count, inlined here so the assertion compares
    * Zobrist against the original implementation even after Phase B's cutover
    * of `GameController.countCurrentPosition` to the Zobrist map.
    */
  private def fenBasedCount(snap: GameSnapshot): Int =
    val currentKey = FenSerializer.positionKey(snap.state)
    val allStates = snap.initialState :: snap.history.map(_._2)
    allStates.count(s => FenSerializer.positionKey(s) == currentKey)

  /** For one state of the snapshot, compare Zobrist count vs FEN count and
    * return a mismatch description if they disagree.
    */
  private def checkCount(
      snap: GameSnapshot,
      label: String
  ): Option[Mismatch] =
    val z = snap.countOf(snap.state)
    val f = fenBasedCount(snap)
    if z == f then None
    else
      Some(
        s"[$label] Zobrist count = $z, FEN count = $f at state ${snap.state}"
      )

  /** Walk a SAN sequence forward, checking equivalence at every ply. */
  private def walkForward(
      seq: List[String]
  ): IO[GameError, List[Mismatch]] =
    val start = GameSnapshot.fresh("eq-test", GameState.initial)
    val initialMismatch = checkCount(start, "initial").toList
    ZIO
      .foldLeft(seq)((start, initialMismatch)) {
        case ((snap, acc), san) =>
          for
            move <- MoveParser.parse(san, snap.state)
            next <- Game.applyMove(snap.state, move)
            advanced = snap.recordMove(move, next)
          yield (advanced, checkCount(advanced, s"after $san").toList ::: acc)
      }
      .map(_._2.reverse)

  /** Walk forward, then undo all the way back. Assert equivalence after each
    * undo step — this verifies that [[GameSnapshot.undoOnce]] correctly
    * decrements counts.
    */
  private def walkForwardThenUndo(
      seq: List[String]
  ): IO[GameError, List[Mismatch]] =
    val start = GameSnapshot.fresh("eq-test", GameState.initial)
    for
      played <- ZIO.foldLeft(seq)(start) { (snap, san) =>
        for
          move <- MoveParser.parse(san, snap.state)
          next <- Game.applyMove(snap.state, move)
        yield snap.recordMove(move, next)
      }
      mismatches <- ZIO.succeed {
        var current = played
        val out = scala.collection.mutable.ListBuffer.empty[Mismatch]
        var step = 0
        while current.history.nonEmpty do
          current.undoOnce match
            case Some(undone) =>
              current = undone
              step += 1
              checkCount(current, s"after undo #$step").foreach(
                out.append(_)
              )
            case None => ()
        out.toList
      }
    yield mismatches

  /** Walk forward, undo all, then redo all. Checks [[GameSnapshot.redoOnce]]
    * correctly re-increments counts.
    */
  private def walkForwardUndoRedo(
      seq: List[String]
  ): IO[GameError, List[Mismatch]] =
    val start = GameSnapshot.fresh("eq-test", GameState.initial)
    for
      played <- ZIO.foldLeft(seq)(start) { (snap, san) =>
        for
          move <- MoveParser.parse(san, snap.state)
          next <- Game.applyMove(snap.state, move)
        yield snap.recordMove(move, next)
      }
      mismatches <- ZIO.succeed {
        val out = scala.collection.mutable.ListBuffer.empty[Mismatch]
        var current = played
        // Undo everything
        while current.history.nonEmpty do
          current.undoOnce match
            case Some(u) => current = u
            case None    => ()
        // Redo everything, checking each step
        var step = 0
        while current.redoStack.nonEmpty do
          current.redoOnce match
            case Some(r) =>
              current = r
              step += 1
              checkCount(current, s"after redo #$step").foreach(
                out.append(_)
              )
            case None => ()
        out.toList
      }
    yield mismatches

  def spec = suite("Zobrist ↔ FEN positionKey equivalence across corpus")(
    test("forward walk: equivalence holds at every ply") {
      for mismatches <- ZIO
          .foreach(corpusSequences)(walkForward)
          .map(_.flatten)
      yield assertTrue(mismatches.isEmpty)
    },
    test("undo: equivalence holds after every undo step") {
      for mismatches <- ZIO
          .foreach(corpusSequences)(walkForwardThenUndo)
          .map(_.flatten)
      yield assertTrue(mismatches.isEmpty)
    },
    test(
      "undo + redo: equivalence holds after every redo step back to the end"
    ) {
      for mismatches <- ZIO
          .foreach(corpusSequences)(walkForwardUndoRedo)
          .map(_.flatten)
      yield assertTrue(mismatches.isEmpty)
    },
    test(
      "replaceHead: Zobrist count unchanged after status-only state swap"
    ) {
      // claimDraw and fivefold auto-draw call replaceHead with a state that
      // differs from the previous top only in `status`. Zobrist.hash ignores
      // status, so positionCounts must not change.
      val seq = List("Nf3", "Nc6", "Ng1", "Nb8")
      val start = GameSnapshot.fresh("eq-test", GameState.initial)
      for
        advanced <- ZIO.foldLeft(seq)(start) { (snap, san) =>
          for
            move <- MoveParser.parse(san, snap.state)
            next <- Game.applyMove(snap.state, move)
          yield snap.recordMove(move, next)
        }
        beforeCounts = advanced.positionCounts
        drawState = advanced.state.copy(
          status = chess.model.board.GameStatus.Draw(
            chess.model.board.DrawReason.ThreefoldRepetition
          )
        )
        afterReplace = advanced.replaceHead(drawState)
      yield assertTrue(
        beforeCounts == afterReplace.positionCounts,
        afterReplace.state.status == chess.model.board.GameStatus
          .Draw(chess.model.board.DrawReason.ThreefoldRepetition)
      )
    }
  )

package chess.controller

import chess.model.{GameError, GameSnapshot, SessionState}
import chess.model.board.{DrawReason, GameStatus}
import chess.service.GameService
import zio.*
import zio.stream.SubscriptionRef

/** Controller-level actions on a game session.
  *
  * Each action uses `session.modifyZIO` so that reading the session, mutating
  * the repository, and committing the new session state happen as a single
  * atomic step. `SubscriptionRef` extends `Ref.Synchronized`, which holds a
  * semaphore during the effect — concurrent callers (e.g. TUI + Web) queue
  * on it rather than racing. If the effect fails, the session is left
  * unchanged, so a failed `makeMove` doesn't corrupt history.
  */
object GameController:

  /** Halfmove clock value at which the 50-move draw rule can be claimed. */
  val FiftyMoveThreshold: Int = 100

  /** Number of times a position must occur for a threefold repetition claim. */
  val ThreefoldThreshold: Int = 3

  /** Number of times a position must occur for an automatic fivefold draw. */
  val FivefoldThreshold: Int = 5

  def makeMove(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      rawInput: String
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      gs.makeMove(s.gameId, rawInput).flatMap { (newState, event) =>
        val provisional = s.game.recordMove(event.move, newState)
        val finalGame =
          if newState.status.isPlaying && isFivefoldRepetition(provisional)
          then
            provisional.replaceHead(
              newState.endWith(GameStatus.Draw(DrawReason.FivefoldRepetition))
            )
          else provisional
        gs.saveState(s.gameId, finalGame.state).as(
          ((), s.copy(game = finalGame, error = None, output = None))
        )
      }
    }

  def undo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      s.game.undoOnce match
        case None =>
          ZIO.fail(GameError.InvalidMove("Nothing to undo"))
        case Some(undone) =>
          gs.saveState(s.gameId, undone.state).as(
            ((), s.copy(game = undone, error = None, output = None))
          )
    }

  def redo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      s.game.redoOnce match
        case None =>
          ZIO.fail(GameError.InvalidMove("Nothing to redo"))
        case Some(redone) =>
          gs.saveState(s.gameId, redone.state).as(
            ((), s.copy(game = redone, error = None, output = None))
          )
    }

  def claimDraw(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.modifyZIO { s =>
      if s.state.status.isOver then
        ZIO.fail(GameError.InvalidMove("Game is already over"))
      else
        val fiftyMoveOk = s.state.halfmoveClock >= FiftyMoveThreshold
        val repetitionCount = countCurrentPosition(s.game)
        val threefoldOk = repetitionCount >= ThreefoldThreshold
        if !fiftyMoveOk && !threefoldOk then
          val fiftyMsg =
            val movesPlayed = s.state.halfmoveClock / 2
            val movesLeft = (FiftyMoveThreshold - s.state.halfmoveClock) / 2
            s"50-move: $movesPlayed of ${FiftyMoveThreshold / 2} moves ($movesLeft to go)"
          val repMsg =
            s"repetition: position occurred $repetitionCount of $ThreefoldThreshold times"
          ZIO.fail(
            GameError.InvalidMove(s"Cannot claim draw — $fiftyMsg; $repMsg")
          )
        else
          val reason =
            if threefoldOk then DrawReason.ThreefoldRepetition
            else DrawReason.FiftyMoveRule
          val drawState = s.state.endWith(GameStatus.Draw(reason))
          gs.saveState(s.gameId, drawState).as(
            (
              (),
              s.copy(
                game = s.game.replaceHead(drawState),
                error = None,
                output = None
              )
            )
          )
    }

  /** Counts how many times the current position has occurred in this game,
    * including the current position itself.
    *
    * Backed by [[GameSnapshot.positionCounts]], an incrementally-maintained
    * Zobrist-keyed map. O(1). Equivalence with the FEN-based implementation
    * is locked down by
    * [[chess.model.rules.RepetitionEquivalenceSpec]] across the full corpus.
    */
  def countCurrentPosition(game: chess.model.GameSnapshot): Int =
    game.countOf(game.state)

  /** Checks whether the current position has occurred five or more times
    * (automatic draw per FIDE rules).
    */
  def isFivefoldRepetition(game: chess.model.GameSnapshot): Boolean =
    countCurrentPosition(game) >= FivefoldThreshold

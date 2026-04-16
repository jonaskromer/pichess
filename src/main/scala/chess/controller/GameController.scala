package chess.controller

import chess.model.{GameError, GameSnapshot, SessionState}
import chess.model.board.{DrawReason, GameState, GameStatus}
import chess.service.GameService
import zio.*
import zio.stream.SubscriptionRef

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
    session.get.flatMap { s =>
      gs.makeMove(s.gameId, rawInput).flatMap { (newState, event) =>
        val provisional = s.game.recordMove(event.move, newState)
        val finalGame =
          if newState.status == GameStatus.Playing && isFivefoldRepetition(
              provisional
            )
          then
            provisional.replaceHead(
              newState.copy(status =
                GameStatus.Draw(DrawReason.FivefoldRepetition)
              )
            )
          else provisional
        gs.saveState(s.gameId, finalGame.state) *>
          session.update(st =>
            st.copy(game = finalGame, error = None, output = None)
          )
      }
    }

  def undo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      s.game.undoOnce match
        case None =>
          ZIO.fail(GameError.InvalidMove("Nothing to undo"))
        case Some(undone) =>
          gs.saveState(s.gameId, undone.state) *>
            session.update(st =>
              st.copy(game = undone, error = None, output = None)
            )
    }

  def redo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      s.game.redoOnce match
        case None =>
          ZIO.fail(GameError.InvalidMove("Nothing to redo"))
        case Some(redone) =>
          gs.saveState(s.gameId, redone.state) *>
            session.update(st =>
              st.copy(game = redone, error = None, output = None)
            )
    }

  def claimDraw(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      if s.state.status != GameStatus.Playing then
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
          val drawState = s.state.copy(status = GameStatus.Draw(reason))
          gs.saveState(s.gameId, drawState) *>
            session.update(st =>
              st.copy(
                game = st.game.replaceHead(drawState),
                error = None,
                output = None
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

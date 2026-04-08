package chess.controller

import chess.codec.FenSerializer
import chess.model.{GameError, SessionState}
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
        val newGame = s.game.copy(
          history = (event.move, newState) :: s.game.history,
          redoStack = Nil
        )
        val finalState =
          if newState.status == GameStatus.Playing && isFivefoldRepetition(
              newGame
            )
          then
            newState
              .copy(status = GameStatus.Draw(DrawReason.FivefoldRepetition))
          else newState
        val finalGame =
          if finalState ne newState then
            newGame.copy(history = (event.move, finalState) :: s.game.history)
          else newGame
        gs.saveState(s.gameId, finalState) *>
          session.update(st => st.copy(game = finalGame, error = None))
      }
    }

  def undo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      s.game.history match
        case Nil =>
          ZIO.fail(GameError.InvalidMove("Nothing to undo"))
        case head :: rest =>
          val prevState =
            rest.headOption.map(_._2).getOrElse(s.game.initialState)
          gs.saveState(s.gameId, prevState) *>
            session.update(st =>
              st.copy(
                game = st.game.copy(
                  history = rest,
                  redoStack = head :: st.game.redoStack
                ),
                error = None
              )
            )
    }

  def redo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      s.game.redoStack match
        case Nil =>
          ZIO.fail(GameError.InvalidMove("Nothing to redo"))
        case (move, state) :: rest =>
          gs.saveState(s.gameId, state) *>
            session.update(st =>
              st.copy(
                game = st.game.copy(
                  history = (move, state) :: st.game.history,
                  redoStack = rest
                ),
                error = None
              )
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
          val (m, _) :: rest = s.game.history: @unchecked
          gs.saveState(s.gameId, drawState) *>
            session.update(st =>
              st.copy(
                game = st.game.copy(history = (m, drawState) :: rest),
                error = None
              )
            )
    }

  /** Computes a position key for repetition comparison. Per FIDE rules, two
    * positions are "the same" when: same pieces on same squares, same active
    * color, same castling rights, same en passant rights. This is the first
    * four FEN fields.
    */
  def positionKey(state: GameState): String =
    FenSerializer.positionKey(state)

  /** Counts how many times the current position has occurred in the game,
    * including the current position itself.
    */
  def countCurrentPosition(game: chess.model.GameSnapshot): Int =
    val currentKey = positionKey(game.state)
    val allStates = game.initialState :: game.history.map(_._2)
    allStates.count(s => positionKey(s) == currentKey)

  /** Checks whether the current position has occurred five or more times
    * (automatic draw per FIDE rules).
    */
  def isFivefoldRepetition(game: chess.model.GameSnapshot): Boolean =
    countCurrentPosition(game) >= FivefoldThreshold

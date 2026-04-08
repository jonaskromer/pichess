package chess.controller

import chess.model.{GameError, SessionState}
import chess.model.board.{DrawReason, GameStatus}
import chess.model.rules.{Game, GameReplay}
import chess.service.GameService
import zio.*
import zio.stream.SubscriptionRef

object GameController:

  /** Halfmove clock value at which the 50-move draw rule can be claimed. */
  val FiftyMoveThreshold: Int = 100

  def makeMove(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      rawInput: String
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      gs.makeMove(s.gameId, rawInput).flatMap { (newState, event) =>
        session.update(st =>
          st.copy(
            game = st.game.copy(
              moves = st.game.moves :+ event.move,
              redoStack = Nil,
              state = newState
            ),
            error = None
          )
        )
      }
    }

  def undo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      if s.game.moves.isEmpty then
        ZIO.fail(GameError.InvalidMove("Nothing to undo"))
      else
        val lastMove = s.game.moves.last
        val newMoves = s.game.moves.init
        GameReplay.replay(s.game.initialState, newMoves).flatMap { newState =>
          gs.saveState(s.gameId, newState) *>
            session.update(st =>
              st.copy(
                game = st.game.copy(
                  moves = newMoves,
                  redoStack = lastMove :: st.game.redoStack,
                  state = newState
                ),
                error = None
              )
            )
        }
    }

  def redo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      s.game.redoStack match
        case Nil =>
          ZIO.fail(GameError.InvalidMove("Nothing to redo"))
        case next :: rest =>
          Game.applyMove(s.game.state, next).flatMap { newState =>
            gs.saveState(s.gameId, newState) *>
              session.update(st =>
                st.copy(
                  game = st.game.copy(
                    moves = st.game.moves :+ next,
                    redoStack = rest,
                    state = newState
                  ),
                  error = None
                )
              )
          }
    }

  def claimDraw(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      if s.state.status != GameStatus.Playing then
        ZIO.fail(GameError.InvalidMove("Game is already over"))
      else if s.state.halfmoveClock < FiftyMoveThreshold then
        val movesLeft = (FiftyMoveThreshold - s.state.halfmoveClock) / 2
        ZIO.fail(
          GameError.InvalidMove(
            s"Cannot claim draw: only ${s.state.halfmoveClock / 2} moves without a pawn move or capture (need ${FiftyMoveThreshold / 2}, ${movesLeft} more to go)"
          )
        )
      else
        val drawState = s.state.copy(status = GameStatus.Draw(DrawReason.FiftyMoveRule))
        gs.saveState(s.gameId, drawState) *>
          session.update(st =>
            st.copy(
              game = st.game.copy(state = drawState),
              error = None
            )
          )
    }

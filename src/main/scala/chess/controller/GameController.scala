package chess.controller

import chess.model.{GameError, SessionState}
import chess.notation.SanSerializer
import chess.service.GameService
import zio.*
import zio.stream.SubscriptionRef

object GameController:

  def makeMove(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      rawInput: String
  ): IO[GameError, Unit] =
    session.get.flatMap { s =>
      gs.makeMove(s.gameId, rawInput).flatMap { (newState, event) =>
        SanSerializer.toSan(event.move, s.state).flatMap { san =>
          session.update(st =>
            st.copy(
              game = st.game.copy(
                state = newState,
                moveLog = st.moveLog :+ (s.state.activeColor, san)
              ),
              error = None
            )
          )
        }
      }
    }

package chess.model

import chess.model.board.{GameState, Move, Position}
import zio.test.*

object GameEventSpec extends ZIOSpecDefault:

  private val id = "game-1"
  private val state = GameState.initial
  private val move = Move(Position('e', 2), Position('e', 4))

  def spec = suite("GameEvent")(
    test("GameStarted should hold the game id and initial state") {
      val event: GameEvent.GameStarted = GameEvent.GameStarted(id, state)
      assertTrue(
        event.gameId == id,
        event.initialState == state
      )
    },
    test("MoveMade should hold the game id, move, and resulting state") {
      val event: GameEvent.MoveMade = GameEvent.MoveMade(id, move, state)
      assertTrue(
        event.gameId == id,
        event.move == move,
        event.resultingState == state
      )
    },
    test("InvalidMoveAttempted should hold the game id and reason") {
      val event: GameEvent.InvalidMoveAttempted =
        GameEvent.InvalidMoveAttempted(id, "No piece at e4")
      assertTrue(
        event.gameId == id,
        event.reason == "No piece at e4"
      )
    }
  )

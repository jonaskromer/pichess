package chess.model

import chess.model.board.{GameState, Move, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameEventSpec extends AnyFlatSpec with Matchers:

  private val id = "game-1"
  private val state = GameState.initial
  private val move = Move(Position('e', 2), Position('e', 4))

  "GameEvent.GameStarted" should "hold the game id and initial state" in:
    val event: GameEvent.GameStarted = GameEvent.GameStarted(id, state)
    event.gameId shouldBe id
    event.initialState shouldBe state

  "GameEvent.MoveMade" should "hold the game id, move, and resulting state" in:
    val event: GameEvent.MoveMade = GameEvent.MoveMade(id, move, state)
    event.gameId shouldBe id
    event.move shouldBe move
    event.resultingState shouldBe state

  "GameEvent.InvalidMoveAttempted" should "hold the game id and reason" in:
    val event: GameEvent.InvalidMoveAttempted =
      GameEvent.InvalidMoveAttempted(id, "No piece at e4")
    event.gameId shouldBe id
    event.reason should include("e4")

package chess.controller

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{GameState, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameControllerSpec extends AnyFlatSpec with Matchers:

  private val initial = GameState.initial

  "GameController.handleInput" should "return None for 'quit'" in:
    GameController.handleInput(initial, "quit") shouldBe None

  it should "return Some(Right(newState)) for a valid move" in:
    val result = GameController.handleInput(initial, "e2 e4")
    result shouldBe defined
    result.get.isRight shouldBe true
    result.get.toOption.get.board.get(Position('e', 4)) shouldBe Some(
      Piece(Color.White, PieceType.Pawn)
    )

  it should "return Some(Left(error)) for an illegal move" in:
    val result = GameController.handleInput(initial, "e2 e5")
    result shouldBe defined
    result.get.isLeft shouldBe true

  it should "return Some(Left(error)) for a parse error" in:
    val result = GameController.handleInput(initial, "garbage")
    result shouldBe defined
    result.get.isLeft shouldBe true

  it should "return Some(Left(error)) when moving the opponent's piece" in:
    val result = GameController.handleInput(initial, "e7 e5")
    result shouldBe defined
    result.get.isLeft shouldBe true

package chess.controller

import chess.model.board.{Move, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MoveParserSpec extends AnyFlatSpec with Matchers:

  "MoveParser.parse" should "parse a valid move" in:
    MoveParser.parse("e2 e4") shouldBe Right(Move(Position('e', 2), Position('e', 4)))

  it should "parse a move using corner squares" in:
    MoveParser.parse("a1 h8") shouldBe Right(Move(Position('a', 1), Position('h', 8)))

  it should "reject input with only one token" in:
    MoveParser.parse("e2") shouldBe a[Left[?, ?]]

  it should "reject input with three or more tokens" in:
    MoveParser.parse("e2 e4 d5") shouldBe a[Left[?, ?]]

  it should "reject empty input" in:
    MoveParser.parse("") shouldBe a[Left[?, ?]]

  it should "reject an invalid column in the source position" in:
    val result = MoveParser.parse("i2 e4")
    result shouldBe a[Left[?, ?]]
    result.left.get should include("i")

  it should "reject a row above 8 in the source position" in:
    val result = MoveParser.parse("e9 e4")
    result shouldBe a[Left[?, ?]]
    result.left.get should include("9")

  it should "reject a row of 0 in the source position" in:
    val result = MoveParser.parse("e0 e4")
    result shouldBe a[Left[?, ?]]
    result.left.get should include("0")

  it should "reject an invalid column in the destination position" in:
    val result = MoveParser.parse("e2 z4")
    result shouldBe a[Left[?, ?]]
    result.left.get should include("z")

  it should "reject an invalid row in the destination position" in:
    val result = MoveParser.parse("e2 e9")
    result shouldBe a[Left[?, ?]]
    result.left.get should include("9")

  it should "reject a position token that is too long" in:
    MoveParser.parse("e22 e4") shouldBe a[Left[?, ?]]

  it should "reject a position token that is too short" in:
    MoveParser.parse("e e4") shouldBe a[Left[?, ?]]

  it should "parse a move with multiple spaces between positions" in:
    MoveParser.parse("e2  e4") shouldBe Right(Move(Position('e', 2), Position('e', 4)))

  it should "include a format hint in error messages" in:
    val result = MoveParser.parse("nonsense")
    result.left.get should include("e2 e4")

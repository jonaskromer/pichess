package chess.view

import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WebBoardViewSpec extends AnyFlatSpec with Matchers:

  private val json = WebBoardView.toJson(GameState.initial, Nil, None)

  "WebBoardView.toJson" should "produce valid JSON containing all 64 squares" in:
    json should include("\"squares\":[")
    json.split("\"pos\":").length shouldBe 65 // 64 entries + 1 before first

  it should "include all initial white piece positions" in:
    json should include(""""pos":"a1"""")
    json should include(""""pos":"e1"""")
    json should include(""""pos":"a2"""")

  it should "include all initial black piece positions" in:
    json should include(""""pos":"a8"""")
    json should include(""""pos":"e8"""")
    json should include(""""pos":"a7"""")

  it should "contain white piece unicode characters" in:
    json should include("♔")
    json should include("♕")
    json should include("♖")
    json should include("♗")
    json should include("♘")
    json should include("♙")

  it should "contain black piece unicode characters" in:
    json should include("♚")
    json should include("♛")
    json should include("♜")
    json should include("♝")
    json should include("♞")
    json should include("♟")

  it should "have null piece for empty squares" in:
    json should include(
      """"pos":"e4","squareColor":"light","piece":null,"pieceColor":null"""
    )

  it should "set activeColor to white for initial state" in:
    json should include(""""activeColor":"white"""")

  it should "have empty moveLog for initial state" in:
    json should include(""""moveLog":[]""")

  it should "have null error when no error provided" in:
    json should include(""""error":null""")

  it should "include square colors" in:
    // a1 has (0 + 1) % 2 == 1 => dark
    json should include(""""pos":"a1","squareColor":"dark"""")
    // b1 has (1 + 1) % 2 == 0 => light
    json should include(""""pos":"b1","squareColor":"light"""")

  it should "include piece colors" in:
    json should include(""""pieceColor":"white"""")
    json should include(""""pieceColor":"black"""")

  "WebBoardView.toJson with move log" should "serialize move entries" in:
    val log = List((Color.White, "e4"), (Color.Black, "e5"))
    val result = WebBoardView.toJson(GameState.initial, log, None)
    result should include(""""color":"white","san":"e4"""")
    result should include(""""color":"black","san":"e5"""")

  "WebBoardView.toJson with error" should "include the error message" in:
    val result =
      WebBoardView.toJson(GameState.initial, Nil, Some("Invalid move"))
    result should include(""""error":"Invalid move"""")

  "escapeJson" should "escape double quotes" in:
    WebBoardView.escapeJson("""say "hello"""") shouldBe """say \"hello\""""

  it should "escape backslashes" in:
    WebBoardView.escapeJson("""a\b""") shouldBe """a\\b"""

  it should "escape newlines" in:
    WebBoardView.escapeJson("a\nb") shouldBe """a\nb"""

  it should "escape carriage returns" in:
    WebBoardView.escapeJson("a\rb") shouldBe """a\rb"""

  it should "escape tabs" in:
    WebBoardView.escapeJson("a\tb") shouldBe """a\tb"""

  it should "leave normal characters unchanged" in:
    WebBoardView.escapeJson("hello world") shouldBe "hello world"

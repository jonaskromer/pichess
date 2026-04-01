package chess.view

import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

object WebBoardViewSpec extends ZIOSpecDefault:

  private val json = WebBoardView.toJson(GameState.initial, Nil, None)

  def spec = suite("WebBoardView")(
    suite("toJson")(
      test("produce valid JSON containing all 64 squares") {
        assertTrue(
          json.contains("\"squares\":["),
          json.split("\"pos\":").length == 65
        )
      },
      test("include all initial white piece positions") {
        assertTrue(
          json.contains(""""pos":"a1""""),
          json.contains(""""pos":"e1""""),
          json.contains(""""pos":"a2"""")
        )
      },
      test("include all initial black piece positions") {
        assertTrue(
          json.contains(""""pos":"a8""""),
          json.contains(""""pos":"e8""""),
          json.contains(""""pos":"a7"""")
        )
      },
      test("contain white piece unicode characters") {
        assertTrue(
          json.contains("♔"),
          json.contains("♕"),
          json.contains("♖"),
          json.contains("♗"),
          json.contains("♘"),
          json.contains("♙")
        )
      },
      test("contain black piece unicode characters") {
        assertTrue(
          json.contains("♚"),
          json.contains("♛"),
          json.contains("♜"),
          json.contains("♝"),
          json.contains("♞"),
          json.contains("♟")
        )
      },
      test("have null piece for empty squares") {
        assertTrue(
          json.contains(
            """"pos":"e4","squareColor":"light","piece":null,"pieceColor":null"""
          )
        )
      },
      test("set activeColor to white for initial state") {
        assertTrue(json.contains(""""activeColor":"white""""))
      },
      test("have empty moveLog for initial state") {
        assertTrue(json.contains(""""moveLog":[]"""))
      },
      test("have null error when no error provided") {
        assertTrue(json.contains(""""error":null"""))
      },
      test("include square colors") {
        assertTrue(
          json.contains(""""pos":"a1","squareColor":"dark""""),
          json.contains(""""pos":"b1","squareColor":"light"""")
        )
      },
      test("include piece colors") {
        assertTrue(
          json.contains(""""pieceColor":"white""""),
          json.contains(""""pieceColor":"black"""")
        )
      }
    ),
    suite("toJson with move log")(
      test("serialize move entries") {
        val log = List((Color.White, "e4"), (Color.Black, "e5"))
        val result = WebBoardView.toJson(GameState.initial, log, None)
        assertTrue(
          result.contains(""""color":"white","san":"e4""""),
          result.contains(""""color":"black","san":"e5"""")
        )
      }
    ),
    suite("toJson with error")(
      test("include the error message") {
        val result =
          WebBoardView.toJson(GameState.initial, Nil, Some("Invalid move"))
        assertTrue(result.contains(""""error":"Invalid move""""))
      }
    ),
    suite("escapeJson")(
      test("escape double quotes") {
        assertTrue(
          WebBoardView.escapeJson("""say "hello"""") == """say \"hello\""""
        )
      },
      test("escape backslashes") {
        assertTrue(WebBoardView.escapeJson("""a\b""") == """a\\b""")
      },
      test("escape newlines") {
        assertTrue(WebBoardView.escapeJson("a\nb") == """a\nb""")
      },
      test("escape carriage returns") {
        assertTrue(WebBoardView.escapeJson("a\rb") == """a\rb""")
      },
      test("escape tabs") {
        assertTrue(WebBoardView.escapeJson("a\tb") == """a\tb""")
      },
      test("leave normal characters unchanged") {
        assertTrue(WebBoardView.escapeJson("hello world") == "hello world")
      }
    )
  )

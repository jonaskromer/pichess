package chess.view

import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.json.*
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
    suite("toJson with check")(
      test("include inCheck and checkedKingPos when in check") {
        val state = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White,
          inCheck = true
        )
        val result = WebBoardView.toJson(state, Nil, None)
        assertTrue(
          result.contains(""""inCheck":true"""),
          result.contains(""""checkedKingPos":"e1"""")
        )
      },
      test("have null checkedKingPos when not in check") {
        val result = WebBoardView.toJson(GameState.initial, Nil, None)
        assertTrue(
          result.contains(""""inCheck":false"""),
          result.contains(""""checkedKingPos":null""")
        )
      }
    ),
    suite("error-message escaping")(
      test("escape embedded double quotes in error messages") {
        val result =
          WebBoardView.toJson(GameState.initial, Nil, Some("""say "hello""""))
        // zio-json renders the escaped error inside the JSON string value.
        assertTrue(result.contains("""\"hello\""""))
      },
      test("escape control characters that the previous hand-rolled escape missed") {
        // Regression: the previous escapeJson did not escape \u0000–\u001f
        // outside \n \r \t, so error messages containing such characters
        // produced invalid JSON. zio-json emits a \uXXXX escape.
        val result =
          WebBoardView.toJson(GameState.initial, Nil, Some("a\u0001b"))
        assertTrue(result.contains("\\u0001"))
      },
      test("round-trip error message through a JSON parser") {
        val message = "line1\nline2\ttab\"quote\\back"
        val result =
          WebBoardView.toJson(GameState.initial, Nil, Some(message))
        // Using zio-json's parser to confirm the output is valid JSON
        // and the error field decodes back to the original message.
        val decoded = result.fromJson[zio.json.ast.Json]
        val error = decoded.toOption
          .flatMap(_.asObject)
          .flatMap(_.get("error"))
          .flatMap(_.asString)
        assertTrue(error.contains(message))
      }
    )
  )

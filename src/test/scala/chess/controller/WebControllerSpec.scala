package chess.controller

import chess.model.SessionState
import chess.model.board.GameState
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.Position
import zio.*
import zio.test.*

object WebControllerSpec extends ZIOSpecDefault:

  def spec = suite("WebController")(
    suite("extractMove")(
      test("parse a move from valid JSON") {
        assertTrue(WebController.extractMove("""{"move":"e2 e4"}""") == Some("e2 e4"))
      },
      test("parse a move with extra whitespace in JSON") {
        assertTrue(WebController.extractMove("""{ "move" : "Nf3" }""") == Some("Nf3"))
      },
      test("return None for JSON without a move field") {
        assertTrue(WebController.extractMove("""{"other":"value"}""").isEmpty)
      },
      test("return None for empty string") {
        assertTrue(WebController.extractMove("").isEmpty)
      },
      test("parse promotion notation") {
        assertTrue(WebController.extractMove("""{"move":"e7 e8=Q"}""") == Some("e7 e8=Q"))
      }
    ),
    suite("SessionState")(
      test("hold game state with empty defaults") {
        val state = SessionState("id", GameState.initial, Nil, None)
        assertTrue(
          state.gameId == "id",
          state.state == GameState.initial,
          state.moveLog == Nil,
          state.error.isEmpty
        )
      },
      test("hold game state with error") {
        val state = SessionState("id", GameState.initial, Nil, Some("oops"))
        assertTrue(state.error == Some("oops"))
      },
      test("hold game state with move log") {
        val log = List((Color.White, "e4"), (Color.Black, "e5"))
        val state = SessionState("id", GameState.initial, log, None)
        assertTrue(state.moveLog == log)
      }
    )
  )

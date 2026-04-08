package chess.controller

import chess.model.{GameSnapshot, SessionState}
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

object WebControllerSpec extends ZIOSpecDefault:

  def spec = suite("WebController")(
    suite("extractMove")(
      test("parse a move from valid JSON") {
        assertTrue(
          WebController.extractMove("""{"move":"e2 e4"}""") == Some("e2 e4")
        )
      },
      test("parse a move with extra whitespace in JSON") {
        assertTrue(
          WebController.extractMove("""{ "move" : "Nf3" }""") == Some("Nf3")
        )
      },
      test("return None for JSON without a move field") {
        assertTrue(WebController.extractMove("""{"other":"value"}""").isEmpty)
      },
      test("return None for empty string") {
        assertTrue(WebController.extractMove("").isEmpty)
      },
      test("parse promotion notation") {
        assertTrue(
          WebController.extractMove("""{"move":"e7 e8=Q"}""") == Some("e7 e8=Q")
        )
      }
    ),
    suite("SessionState")(
      test("hold game state with empty defaults") {
        val state = SessionState(GameSnapshot("id", GameState.initial))
        assertTrue(
          state.gameId == "id",
          state.state == GameState.initial,
          state.moves == Nil,
          state.error.isEmpty
        )
      },
      test("hold game state with error") {
        val state = SessionState(
          GameSnapshot("id", GameState.initial),
          error = Some("oops")
        )
        assertTrue(state.error == Some("oops"))
      },
      test("hold game state with moves") {
        val move = Move(Position('e', 2), Position('e', 4))
        val state = SessionState(
          GameSnapshot("id", GameState.initial, history = List((move, GameState.initial)))
        )
        assertTrue(state.moves == List(move))
      }
    )
  )

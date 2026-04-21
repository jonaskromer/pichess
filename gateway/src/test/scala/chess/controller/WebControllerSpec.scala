package chess.controller

import chess.model.{GameSnapshot, SessionState}
import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

object WebControllerSpec extends ZIOSpecDefault:

  def spec = suite("WebController")(
    suite("SessionState")(
      test("hold game state with empty defaults") {
        val state = SessionState(GameSnapshot.fresh("id", GameState.initial))
        assertTrue(
          state.gameId == "id",
          state.state == GameState.initial,
          state.moves == Nil,
          state.error.isEmpty
        )
      },
      test("hold game state with error") {
        val state = SessionState(
          GameSnapshot.fresh("id", GameState.initial),
          error = Some("oops")
        )
        assertTrue(state.error == Some("oops"))
      },
      test("hold game state with moves") {
        val move = Move(Position('e', 2), Position('e', 4))
        val state = SessionState(
          GameSnapshot.fromHistory(
            "id",
            GameState.initial,
            List((move, GameState.initial))
          )
        )
        assertTrue(state.moves == List(move))
      }
    )
  )

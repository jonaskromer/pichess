package chess.repository

import chess.model.board.GameState
import chess.model.piece.Color
import zio.*
import zio.test.*

object GameRepositorySpec extends ZIOSpecDefault:

  def spec = suite("InMemoryGameRepository")(
    test("return None for an unknown game id") {
      for result <- GameRepository.load("unknown")
      yield assertTrue(result.isEmpty)
    },
    test("save and load a game state") {
      val state = GameState.initial
      for
        _ <- GameRepository.save("g1", state)
        result <- GameRepository.load("g1")
      yield assertTrue(result == Some(state))
    },
    test("overwrite an existing game on save") {
      val s1 = GameState.initial
      val s2 = s1.copy(activeColor = Color.Black)
      for
        _ <- GameRepository.save("g1", s1)
        _ <- GameRepository.save("g1", s2)
        result <- GameRepository.load("g1")
      yield assertTrue(result == Some(s2))
    },
    test("return None after deleting a game") {
      for
        _ <- GameRepository.save("g1", GameState.initial)
        _ <- GameRepository.delete("g1")
        result <- GameRepository.load("g1")
      yield assertTrue(result.isEmpty)
    },
    test("isolate games by id") {
      for
        _ <- GameRepository.save("g1", GameState.initial)
        result <- GameRepository.load("g2")
      yield assertTrue(result.isEmpty)
    }
  ).provide(InMemoryGameRepository.layer)

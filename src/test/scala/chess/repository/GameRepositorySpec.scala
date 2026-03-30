package chess.repository

import chess.model.board.GameState
import chess.model.piece.Color
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

class GameRepositorySpec extends AnyFlatSpec with Matchers:

  private def run[A](task: ZIO[GameRepository, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(task.provide(InMemoryGameRepository.layer))
        .getOrThrowFiberFailure()
    }

  "InMemoryGameRepository" should "return None for an unknown game id" in:
    run(GameRepository.load("unknown")) shouldBe None

  it should "save and load a game state" in:
    val state = GameState.initial
    run(GameRepository.save("g1", state) *> GameRepository.load("g1")) shouldBe Some(state)

  it should "overwrite an existing game on save" in:
    val s1 = GameState.initial
    val s2 = s1.copy(activeColor = Color.Black)
    run(
      GameRepository.save("g1", s1) *>
        GameRepository.save("g1", s2) *>
        GameRepository.load("g1")
    ) shouldBe Some(s2)

  it should "return None after deleting a game" in:
    run(
      GameRepository.save("g1", GameState.initial) *>
        GameRepository.delete("g1") *>
        GameRepository.load("g1")
    ) shouldBe None

  it should "isolate games by id" in:
    run(
      GameRepository.save("g1", GameState.initial) *>
        GameRepository.load("g2")
    ) shouldBe None

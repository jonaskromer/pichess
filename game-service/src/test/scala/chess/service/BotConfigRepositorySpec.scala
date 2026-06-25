package chess.service

import zio.*
import zio.test.*

import chess.bot.engine.{BotConfig, Difficulty}
import chess.model.piece.Color

object BotConfigRepositorySpec extends ZIOSpecDefault:

  private val sample = BotConfig(
    botSide = Color.Black,
    difficulty = Difficulty.Medium,
    allowUndo = true
  )

  def spec = suite("BotConfigRepository.inMemory")(
    test("get returns None for an unknown game") {
      for
        repo <- BotConfigRepository.inMemory
        got <- repo.get("missing")
      yield assertTrue(got.isEmpty)
    },
    test("save then get round-trips the config") {
      for
        repo <- BotConfigRepository.inMemory
        _ <- repo.save("g1", sample)
        got <- repo.get("g1")
      yield assertTrue(got.contains(sample))
    },
    test("save overwrites an existing config for the same game id") {
      val updated = sample.copy(difficulty = Difficulty.Expert)
      for
        repo <- BotConfigRepository.inMemory
        _ <- repo.save("g1", sample)
        _ <- repo.save("g1", updated)
        got <- repo.get("g1")
      yield assertTrue(got.contains(updated))
    },
    test("delete removes the entry") {
      for
        repo <- BotConfigRepository.inMemory
        _ <- repo.save("g1", sample)
        _ <- repo.delete("g1")
        got <- repo.get("g1")
      yield assertTrue(got.isEmpty)
    },
    test("delete of an unknown id is a no-op") {
      for
        repo <- BotConfigRepository.inMemory
        _ <- repo.delete("never-saved")
      yield assertCompletes
    }
  )

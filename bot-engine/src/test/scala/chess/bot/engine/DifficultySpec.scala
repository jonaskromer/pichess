package chess.bot.engine

import zio.json.*
import zio.test.*

object DifficultySpec extends ZIOSpecDefault:

  def spec = suite("Difficulty")(
    suite("search depth mapping")(
      test("levels are ordered strictly increasing in depth") {
        // The skill ladder is "harder difficulty = deeper search", no
        // ties. A regression that flipped Easy ≥ Medium would silently
        // make the gamemode picker useless.
        assertTrue(
          Difficulty.Beginner.searchDepth < Difficulty.Easy.searchDepth,
          Difficulty.Easy.searchDepth     < Difficulty.Medium.searchDepth,
          Difficulty.Medium.searchDepth   < Difficulty.Hard.searchDepth,
          Difficulty.Hard.searchDepth     < Difficulty.Expert.searchDepth,
        )
      },
      test("noise decreases or stays equal as difficulty increases") {
        // No regression where a "harder" difficulty introduces MORE
        // randomness than an easier one — that'd be nonsensical.
        val levels = Difficulty.values.toList
        val noiseValues = levels.map(_.noise)
        assertTrue(noiseValues == noiseValues.sorted.reverse)
      },
      test("expert plays without randomness") {
        assertTrue(Difficulty.Expert.noise == 0.0)
      },
    ),
    suite("JSON codec")(
      test("round-trips Medium") {
        val d: Difficulty = Difficulty.Medium
        val json = d.toJson
        assertTrue(
          json == "\"Medium\"",
          json.fromJson[Difficulty] == Right(Difficulty.Medium),
        )
      },
      test("case-insensitive decode") {
        assertTrue(
          "\"medium\"".fromJson[Difficulty]  == Right(Difficulty.Medium),
          "\"BEGINNER\"".fromJson[Difficulty] == Right(Difficulty.Beginner),
          "\"hArD\"".fromJson[Difficulty]    == Right(Difficulty.Hard),
        )
      },
      test("rejects unknown values") {
        assertTrue("\"impossible\"".fromJson[Difficulty].isLeft)
      },
    ),
    suite("Default")(
      test("is Medium") {
        assertTrue(Difficulty.Default == Difficulty.Medium)
      },
    ),
  )

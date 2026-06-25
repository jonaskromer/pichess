package chess.bot.engine

import zio.json.*
import zio.test.*

object DifficultySpec extends ZIOSpecDefault:

  def spec = suite("Difficulty")(
    suite("effort ladder")(
      test("depth ceiling is strictly increasing") {
        // The skill ladder is "harder difficulty = at least as deep". A
        // regression that flipped Easy ≥ Medium would make the picker useless.
        assertTrue(
          Difficulty.Beginner.maxDepth < Difficulty.Easy.maxDepth,
          Difficulty.Easy.maxDepth < Difficulty.Medium.maxDepth,
          Difficulty.Medium.maxDepth < Difficulty.Hard.maxDepth,
          Difficulty.Hard.maxDepth < Difficulty.Expert.maxDepth,
          Difficulty.Expert.maxDepth < Difficulty.Max.maxDepth
        )
      },
      test("budget is non-decreasing; weak levels are instant, the rest scale") {
        // Harder = thinks at least as long. Beginner/Easy carry no clock
        // (budget 0 ⇒ the instant fixed-depth path); Medium..Max each get a
        // strictly larger per-move budget than the last.
        val budgets  = Difficulty.values.toList.map(_.budgetMs)
        val budgeted = budgets.filter(_ > 0)
        assertTrue(
          budgets == budgets.sorted,            // non-decreasing across the ladder
          budgeted == budgeted.distinct.sorted, // strictly increasing among budgeted
          Difficulty.Beginner.budgetMs == 0L,
          Difficulty.Easy.budgetMs == 0L
        )
      },
      test("only the weak levels add noise; the rest play it straight") {
        // No regression where a "harder" level is MORE random, and the
        // budgeted levels must be deterministic (they play full strength).
        val noiseValues = Difficulty.values.toList.map(_.noise)
        assertTrue(
          noiseValues == noiseValues.sorted.reverse, // non-increasing
          Difficulty.Beginner.noise > 0.0,
          Difficulty.Easy.noise > 0.0,
          Difficulty.Medium.noise == 0.0,
          Difficulty.Expert.noise == 0.0,
          Difficulty.Max.noise == 0.0
        )
      },
      test("Max is the strongest tier") {
        val others = Difficulty.values.filter(_ != Difficulty.Max).toList
        assertTrue(
          others.forall(_.budgetMs < Difficulty.Max.budgetMs),
          others.forall(_.maxDepth <= Difficulty.Max.maxDepth)
        )
      }
    ),
    suite("JSON codec")(
      test("round-trips Medium") {
        val d: Difficulty = Difficulty.Medium
        val json = d.toJson
        assertTrue(
          json == "\"Medium\"",
          json.fromJson[Difficulty] == Right(Difficulty.Medium)
        )
      },
      test("round-trips the new Max tier") {
        val d: Difficulty = Difficulty.Max
        assertTrue(
          d.toJson == "\"Max\"",
          "\"Max\"".fromJson[Difficulty] == Right(Difficulty.Max)
        )
      },
      test("case-insensitive decode") {
        assertTrue(
          "\"medium\"".fromJson[Difficulty] == Right(Difficulty.Medium),
          "\"BEGINNER\"".fromJson[Difficulty] == Right(Difficulty.Beginner),
          "\"hArD\"".fromJson[Difficulty] == Right(Difficulty.Hard),
          "\"max\"".fromJson[Difficulty] == Right(Difficulty.Max)
        )
      },
      test("rejects unknown values") {
        assertTrue("\"impossible\"".fromJson[Difficulty].isLeft)
      }
    ),
    suite("Default")(
      test("is Medium") {
        assertTrue(Difficulty.Default == Difficulty.Medium)
      }
    )
  )

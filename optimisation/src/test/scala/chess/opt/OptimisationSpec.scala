package chess.opt

import zio.*
import zio.test.*

object OptimisationSpec extends ZIOSpecDefault:

  /** Test fixture — a String-valued Optimisation so we can A/B without
    * needing a real ZIO service shape.
    */
  given Optimisation[String] with
    val name     = "TEST_KEY"
    val default  = ZLayer.succeed("default")
    val baseline = ZLayer.succeed("baseline")

  private def env(pairs: (String, String)*): String => Option[String] =
    pairs.toMap.get

  private def chosen(envFn: String => Option[String]): UIO[String] =
    ZIO.scoped(Optimisation.selectWith[String](envFn).build.map(_.get)).orDie

  def spec = suite("Optimisation")(
    test("default when no env is set") {
      chosen(env()).map(s => assertTrue(s == "default"))
    },
    test("baseline when PICHESS_OPT_<name>=baseline") {
      chosen(env("PICHESS_OPT_TEST_KEY" -> "baseline"))
        .map(s => assertTrue(s == "baseline"))
    },
    test("default when PICHESS_OPT_<name>=default (explicit)") {
      chosen(env("PICHESS_OPT_TEST_KEY" -> "default"))
        .map(s => assertTrue(s == "default"))
    },
    test("PICHESS_OPT_ALL=baseline flips when no per-component setting") {
      chosen(env("PICHESS_OPT_ALL" -> "baseline"))
        .map(s => assertTrue(s == "baseline"))
    },
    test("per-component setting overrides PICHESS_OPT_ALL") {
      chosen(env(
        "PICHESS_OPT_ALL"      -> "baseline",
        "PICHESS_OPT_TEST_KEY" -> "default",
      )).map(s => assertTrue(s == "default"))
    },
    test("value matching is case-insensitive") {
      chosen(env("PICHESS_OPT_TEST_KEY" -> "BaSeLiNe"))
        .map(s => assertTrue(s == "baseline"))
    },
    test("unknown value falls back to default") {
      chosen(env("PICHESS_OPT_TEST_KEY" -> "what-even"))
        .map(s => assertTrue(s == "default"))
    },
    test("PICHESS_OPT_ALL with non-baseline value is ignored") {
      chosen(env("PICHESS_OPT_ALL" -> "default"))
        .map(s => assertTrue(s == "default"))
    },
    test("useBaseline mirrors selectWith") {
      val cases = List(
        env()                                                          -> false,
        env("PICHESS_OPT_TEST_KEY" -> "baseline")                      -> true,
        env("PICHESS_OPT_TEST_KEY" -> "default")                       -> false,
        env("PICHESS_OPT_ALL" -> "baseline")                           -> true,
        env("PICHESS_OPT_ALL" -> "baseline",
            "PICHESS_OPT_TEST_KEY" -> "default")                       -> false,
      )
      assertTrue(
        cases.forall { case (e, expected) =>
          Optimisation.useBaseline("TEST_KEY", e) == expected
        }
      )
    },
    test("select(sys.env.get) constructs a layer") {
      // Production entry point — exercises the line that delegates to
      // sys.env.get, which testWith can't otherwise cover.
      ZIO
        .scoped(Optimisation.select[String].build.map(_.get))
        .map(s => assertTrue(s == "default" || s == "baseline"))
    },
  )

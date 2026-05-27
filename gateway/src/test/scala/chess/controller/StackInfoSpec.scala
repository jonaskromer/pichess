package chess.controller

import zio.*
import zio.test.*

object StackInfoSpec extends ZIOSpecDefault:

  /** Set the three env vars StackInfo reads, then run `fromEnv`.
    * Uses ZIO test's `TestSystem` (provided by default to every
    * ZIOSpecDefault) so each test gets an isolated env snapshot.
    */
  private def withEnv(
      stack:   Option[String] = None,
      extras:  Option[String] = None,
      devMode: Option[String] = None
  ): UIO[StackInfo] =
    val sets =
      ZIO.foreachDiscard(stack)(v   => TestSystem.putEnv(StackInfo.EnvBackend, v)) *>
      ZIO.foreachDiscard(extras)(v  => TestSystem.putEnv(StackInfo.EnvExtras,  v)) *>
      ZIO.foreachDiscard(devMode)(v => TestSystem.putEnv(StackInfo.EnvDevMode, v))
    sets *> StackInfo.fromEnv

  def spec = suite("StackInfo.fromEnv")(
    test("defaults to inmemory + empty extras + devMode=false when no env vars set") {
      for info <- withEnv()
      yield assertTrue(
        info.backend == "inmemory",
        info.extras.isEmpty,
        info.devMode == false
      )
    },
    test("reads PICHESS_STACK as the backend") {
      for info <- withEnv(stack = Some("postgres"))
      yield assertTrue(info.backend == "postgres")
    },
    test("trims whitespace from PICHESS_STACK") {
      for info <- withEnv(stack = Some("  mongo  "))
      yield assertTrue(info.backend == "mongo")
    },
    test("falls back to default when PICHESS_STACK is blank") {
      for info <- withEnv(stack = Some("   "))
      yield assertTrue(info.backend == StackInfo.Default.backend)
    },
    test("splits PICHESS_STACK_EXTRAS on commas") {
      for info <- withEnv(extras = Some("opening,analytics"))
      yield assertTrue(info.extras == List("opening", "analytics"))
    },
    test("trims and drops empty entries in PICHESS_STACK_EXTRAS") {
      for info <- withEnv(extras = Some("  opening , , analytics ,"))
      yield assertTrue(info.extras == List("opening", "analytics"))
    },
    test("empty PICHESS_STACK_EXTRAS yields no extras") {
      for info <- withEnv(extras = Some(""))
      yield assertTrue(info.extras.isEmpty)
    },
    test("recognises PICHESS_DEV=true as truthy") {
      for info <- withEnv(devMode = Some("true"))
      yield assertTrue(info.devMode)
    },
    test("recognises PICHESS_DEV in mixed case") {
      for info <- withEnv(devMode = Some("TRUE"))
      yield assertTrue(info.devMode)
    },
    test("recognises 1 / yes / on as truthy") {
      for
        one <- withEnv(devMode = Some("1"))
        yes <- withEnv(devMode = Some("yes"))
        on  <- withEnv(devMode = Some("on"))
      yield assertTrue(one.devMode, yes.devMode, on.devMode)
    },
    test("rejects anything else as falsy") {
      for
        falseVal <- withEnv(devMode = Some("false"))
        bogus    <- withEnv(devMode = Some("nope"))
        empty    <- withEnv(devMode = Some(""))
      yield assertTrue(
        !falseVal.devMode,
        !bogus.devMode,
        !empty.devMode
      )
    }
  )

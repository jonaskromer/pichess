package chess.bot.train

import zio.test.*

import chess.bot.engine.PolicyPrior

object PolicyPriorMainSpec extends ZIOSpecDefault:

  private def idx(uci: String): Int =
    PolicyPriorMain.sq(uci, 0) * 64 + PolicyPriorMain.sq(uci.substring(2), 0)

  def spec = suite("PolicyPriorMain")(
    test("sq parses UCI squares to LERF indices (matches MoveInt)") {
      assertTrue(
        PolicyPriorMain.sq("e2", 0) == 12,
        PolicyPriorMain.sq("e4", 0) == 28,
        PolicyPriorMain.sq("a1", 0) == 0,
        PolicyPriorMain.sq("h8", 0) == 63,
        PolicyPriorMain.sq("zz", 0) == -1,
      )
    },
    test("accumulate + log-normalize ranks frequent SF moves higher") {
      val counts = new Array[Long](PolicyPrior.Size)
      (1 to 3).foreach(_ => PolicyPriorMain.accumulate(counts, "e2e4")) // most common
      PolicyPriorMain.accumulate(counts, "g1f3")
      PolicyPriorMain.accumulate(counts, "bogus")                       // malformed → ignored
      val t = PolicyPriorMain.normalize(counts, 20_000)
      assertTrue(
        t(idx("e2e4")) == 20_000,        // the max maps to maxBonus
        t(idx("g1f3")) > 0,
        t(idx("e2e4")) > t(idx("g1f3")), // more frequent → higher prior
        t(idx("a1a2")) == 0,             // never played → 0
      )
    },
  )

package chess.bot.engine

import zio.test.*

object PolicyPriorSpec extends ZIOSpecDefault:

  def spec = suite("PolicyPrior")(
    test("Empty is a zero-bonus no-op everywhere") {
      assertTrue(
        PolicyPrior.Empty.bonus(12, 28) == 0,
        PolicyPrior.Empty.bonus(0, 63) == 0
      )
    },
    test("toBytes → parse round-trips the from→to table") {
      val t = new Array[Int](PolicyPrior.Size)
      t(12 * 64 + 28) = 17_000 // e2 → e4
      t(0) = 3
      val p = PolicyPrior.parse(PolicyPrior.toBytes(t))
      assertTrue(
        p.bonus(12, 28) == 17_000,
        p.bonus(0, 0) == 3,
        p.bonus(1, 1) == 0
      )
    },
    test("parse rejects a mis-sized buffer") {
      assertTrue(
        scala.util.Try(PolicyPrior.parse(new Array[Byte](10))).isFailure
      )
    }
  )

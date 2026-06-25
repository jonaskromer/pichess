package chess.analysis

import zio.test.*

object WinProbSpec extends ZIOSpecDefault:

  private def near(a: Double, b: Double, eps: Double = 0.5): Boolean = math.abs(a - b) <= eps

  def spec = suite("WinProb")(
    test("pct: 0cp is 50%, large advantage saturates") {
      assertTrue(
        near(WinProb.pct(0), 50.0),
        WinProb.pct(2000) > 97.0,
        WinProb.pct(-2000) < 3.0,
        near(WinProb.pct(100000), 100.0, 0.01)
      )
    },
    test("accuracy: no loss ≈ 100, big loss clamps to 0") {
      assertTrue(
        near(WinProb.accuracy(0.0), 100.0, 0.01),
        WinProb.accuracy(20.0) < 100.0 && WinProb.accuracy(20.0) > 0.0,
        WinProb.accuracy(200.0) == 0.0 // clamped
      )
    }
  )

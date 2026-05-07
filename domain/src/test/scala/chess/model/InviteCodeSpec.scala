package chess.model

import zio.test.*

object InviteCodeSpec extends ZIOSpecDefault:

  def spec = suite("InviteCode")(
    test("apply accepts a 6-char uppercase code from the alphabet") {
      val parsed = InviteCode("ABCDEF")
      assertTrue(parsed.exists(_.value == "ABCDEF"))
    },
    test("apply normalizes to upper-case and trims surrounding whitespace") {
      val parsed = InviteCode("  abcdef  ")
      assertTrue(parsed.exists(_.value == "ABCDEF"))
    },
    test("apply rejects codes of the wrong length") {
      assertTrue(
        InviteCode("ABCDE").isEmpty,
        InviteCode("ABCDEFG").isEmpty,
        InviteCode("").isEmpty
      )
    },
    test("apply rejects codes containing characters outside the alphabet") {
      assertTrue(
        InviteCode("ABCDE!").isEmpty,
        InviteCode("ABCDE0").isEmpty, // 0 excluded (looks like O)
        InviteCode("ABCDE1").isEmpty, // 1 excluded (looks like I)
        InviteCode("ABCDEI").isEmpty,
        InviteCode("ABCDEO").isEmpty
      )
    },
    test("unsafe wraps an arbitrary string without validation") {
      val raw = "anything-goes"
      assertTrue(InviteCode.unsafe(raw).value == raw)
    },
    test("fromRandom emits Length characters drawn only from the alphabet") {
      val nextInt: Int => Int = bound => 0 % bound
      val code = InviteCode.fromRandom(nextInt)
      assertTrue(
        code.value.length == InviteCode.Length,
        code.value == "A" * InviteCode.Length
      )
    },
    test("fromRandom uses every index returned by the source") {
      var i = 0
      val nextInt: Int => Int = bound =>
        val out = i % bound
        i += 1
        out
      val code = InviteCode.fromRandom(nextInt)
      assertTrue(
        code.value.length == InviteCode.Length,
        code.value == "ABCDEF"
      )
    },
    test("random produces a valid Length-character code via ZIO Random") {
      for code <- InviteCode.random
      yield assertTrue(
        code.value.length == InviteCode.Length,
        // Re-parsing through `apply` round-trips, proving every char is in
        // the alphabet (the apply path rejects out-of-alphabet chars).
        InviteCode(code.value).contains(code)
      )
    }
  )

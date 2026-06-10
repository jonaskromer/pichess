package chess.bot.lichess

import zio.test.*

object LichessTablebaseSearchSpec extends ZIOSpecDefault:

  // A realistic Lichess tablebase API response — extra fields included on
  // purpose to prove we decode only `moves[].uci` and ignore the rest.
  private val winResponse =
    """{"category":"win","wdl":2,"dtz":5,"dtm":17,"checkmate":false,"stalemate":false,
      |"moves":[
      |  {"uci":"e6e7","san":"Ke7","wdl":-2,"dtz":-4,"dtm":-16,"category":"loss","zeroing":false},
      |  {"uci":"e6d6","san":"Kd6","wdl":0,"dtz":0,"dtm":0,"category":"draw"}
      |]}""".stripMargin

  def spec = suite("LichessTablebaseSearch.parseBestMove")(
    test("returns the first (best) move, ignoring the extra JSON fields") {
      val m = LichessTablebaseSearch.parseBestMove(winResponse)
      assertTrue(m.map(UciCodec.serialize).contains("e6e7"))
    },
    test("parses a promotion uci") {
      val json = """{"category":"win","moves":[{"uci":"f7f8q","san":"f8=Q","category":"loss"}]}"""
      assertTrue(LichessTablebaseSearch.parseBestMove(json).map(UciCodec.serialize).contains("f7f8q"))
    },
    test("empty moves → None") {
      assertTrue(LichessTablebaseSearch.parseBestMove("""{"category":"draw","moves":[]}""").isEmpty)
    },
    test("malformed / missing-moves JSON → None (fail-safe)") {
      assertTrue(
        LichessTablebaseSearch.parseBestMove("not json").isEmpty,
        LichessTablebaseSearch.parseBestMove("""{"category":"win"}""").isEmpty,
      )
    },
  )

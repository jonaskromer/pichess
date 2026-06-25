package chess.codec

import zio.test.*

/** Co-located encode/decode for PGN clock (`[%clk]`/`[%emt]`) + NAG fields. */
object PgnCodecSpec extends ZIOSpecDefault:

  def spec = suite("PgnCodec")(
    suite("clock [%clk]")(
      test("encode truncates to whole seconds, pads, clamps at 0") {
        assertTrue(
          PgnCodec.encodeClock(90500) == "0:01:30",
          PgnCodec.encodeClock(3661000) == "1:01:01",
          PgnCodec.encodeClock(0) == "0:00:00",
          PgnCodec.encodeClock(-5) == "0:00:00"
        )
      },
      test("decode parses H:MM:SS(.f), rejects malformed") {
        assertTrue(
          PgnCodec.decodeClock("0:01:30") == Some(90000L),
          PgnCodec.decodeClock("1:01:01.5") == Some(3661500L),
          PgnCodec.decodeClock("1:2") == None,        // wrong arity
          PgnCodec.decodeClock("x:01:30") == None,    // bad hours
          PgnCodec.decodeClock("0:x:30") == None,     // bad minutes
          PgnCodec.decodeClock("0:01:x") == None       // bad seconds
        )
      }
    ),
    suite("elapsed move time [%emt]")(
      test("encode keeps one decimal, clamps at 0") {
        assertTrue(
          PgnCodec.encodeEmt(2000) == "2",
          PgnCodec.encodeEmt(1200) == "1.2",
          PgnCodec.encodeEmt(500) == "0.5",
          PgnCodec.encodeEmt(0) == "0",
          PgnCodec.encodeEmt(-9) == "0"
        )
      },
      test("decode parses seconds, rejects non-numeric") {
        assertTrue(
          PgnCodec.decodeEmt("1.2") == Some(1200L),
          PgnCodec.decodeEmt("30") == Some(30000L),
          PgnCodec.decodeEmt("x") == None
        )
      }
    ),
    suite("move comment")(
      test("encodes emt then clk, or None when empty") {
        assertTrue(
          PgnCodec.encodeMoveComment(None, None) == None,
          PgnCodec.encodeMoveComment(Some(90000), None) == Some("{[%clk 0:01:30]}"),
          PgnCodec.encodeMoveComment(None, Some(2000)) == Some("{[%emt 2]}"),
          PgnCodec.encodeMoveComment(Some(90000), Some(2000)) ==
            Some("{[%emt 2] [%clk 0:01:30]}")
        )
      },
      test("extracts clk / emt from a comment body, None when absent") {
        assertTrue(
          PgnCodec.extractClock("{[%clk 0:01:30]}") == Some(90000L),
          PgnCodec.extractEmt("{[%emt 2.5]}") == Some(2500L),
          PgnCodec.extractClock("{just a note}") == None,
          PgnCodec.extractEmt("{just a note}") == None
        )
      }
    ),
    suite("NAG")(
      test("encodes a code as its $n token") {
        assertTrue(PgnCodec.encodeNag(3) == "$3")
      },
      test("symbol ↔ code round-trip, unknown → None") {
        assertTrue(
          Nag.symbol(Nag.Brilliant) == Some("!!"),
          Nag.code("?!") == Some(Nag.Dubious),
          Nag.symbol(99) == None,
          Nag.code("!?!") == None
        )
      }
    )
  )

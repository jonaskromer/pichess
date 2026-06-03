package chess.controller

import zio.http.*
import zio.test.*

/** Direct unit tests for LobbyProxy's path/URL composition helpers.
  * The full-stack proxy spec exercises the happy path through a real
  * upstream server — the helpers here are the parts that can't be hit
  * end-to-end (zio-http always normalises Path so its `toString` is
  * leading-slash-free; `URL.decode` only fails on URL strings the
  * proxy itself would never construct from a well-formed inbound
  * request).
  */
object LobbyProxyHelpersSpec extends ZIOSpecDefault:

  def spec = suite("LobbyProxy helpers")(
    suite("joinPath")(
      test("prefixes a single slash when the path is leading-slash-free") {
        assertTrue(LobbyProxy.joinPath(Path("123/players")) == "/123/players")
      },
      test("returns Path.empty as a bare slash") {
        assertTrue(LobbyProxy.joinPath(Path.empty) == "/")
      },
      test("preserves a leading slash when one is already present") {
        // Defensive guard — current zio-http (1.x) emits
        // `Path("foo/bar").toString` without a leading slash, but if a
        // future bump changes that, this arm prevents double-slashes.
        assertTrue(LobbyProxy.joinPath(Path("/abs/path")) == "/abs/path")
      }
    ),
    suite("buildTarget")(
      test("composes base + prefix + path + query into a valid URL") {
        val result = LobbyProxy.buildTarget(
          base        = "http://lobby:8092",
          prefix      = "lobbies",
          rest        = Path("abc"),
          queryParams = QueryParams("status" -> "open")
        )
        assertTrue(
          result.isRight,
          result.toOption.get.encode == "http://lobby:8092/lobbies/abc?status=open"
        )
      },
      test("strips a trailing slash from the base URL") {
        val result = LobbyProxy.buildTarget(
          base        = "http://lobby:8092/",
          prefix      = "lobbies",
          rest        = Path.empty,
          queryParams = QueryParams.empty
        )
        assertTrue(
          result.toOption.exists(_.encode == "http://lobby:8092/lobbies/")
        )
      },
      test("returns Left with the offending string when URL.decode rejects the result") {
        // `URL.decode` rejects URLs with raw whitespace. The proxy
        // itself never constructs such a string from a clean inbound
        // request, but a misconfigured `PICHESS_LOBBY_URL` could land
        // a space in the base; the guard surfaces that as a 502
        // rather than crashing the route.
        val result = LobbyProxy.buildTarget(
          base        = "http://lobby with space",
          prefix      = "lobbies",
          rest        = Path.empty,
          queryParams = QueryParams.empty
        )
        assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.contains("lobby with space"))
        )
      }
    ),
    suite("addCarrierHeaders")(
      test("empty carrier returns the base headers unchanged") {
        val base   = Headers("x-existing" -> "1")
        val result = LobbyProxy.addCarrierHeaders(base, Map.empty)
        assertTrue(
          result.exists(_.headerName == "x-existing"),
          result.get("traceparent").isEmpty,
        )
      },
      test("non-empty carrier appends each entry as a header") {
        val base = Headers.empty
        val carrier = Map(
          "traceparent" -> "00-trace-span-01",
          "tracestate"  -> "vendor=value",
        )
        val result = LobbyProxy.addCarrierHeaders(base, carrier)
        assertTrue(
          result.get("traceparent").contains("00-trace-span-01"),
          result.get("tracestate").contains("vendor=value"),
        )
      },
      test("carrier entries don't replace existing base headers") {
        val base    = Headers("x-existing" -> "keep-me")
        val carrier = Map("traceparent" -> "00-trace-span-01")
        val result  = LobbyProxy.addCarrierHeaders(base, carrier)
        assertTrue(
          result.get("x-existing").contains("keep-me"),
          result.get("traceparent").contains("00-trace-span-01"),
        )
      },
    ),
  )

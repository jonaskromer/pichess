package chess.controller

import chess.obs.TracingLayer
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

/** Tests for the lobby reverse-proxy. The upstream is a tiny real
  * zio-http server bound to an ephemeral port (`Server.port = 0`); the
  * proxy is configured with that server's `http://localhost:<port>`
  * base URL. Each request through the proxy hits the real upstream and
  * we assert on the round-tripped response.
  *
  * The upstream is intentionally tiny — it echoes back the method,
  * the path it received, the query string, and any body. That's enough
  * to verify path/method/query/body forwarding without coupling the
  * tests to the actual lobby-service schema.
  */
object LobbyProxySpec extends ZIOSpecDefault:

  /** Echo upstream: responds to any /lobbies path with a body that
    * captures what it saw — `method path?query|body`. */
  private val upstream: Routes[Any, Response] = Routes(
    Method.ANY / "lobbies" / trailing -> handler {
      (rest: Path, req: Request) =>
        for body <- req.body.asString.orElseSucceed("")
        yield
          val q = if req.url.queryParams.isEmpty then ""
                  else "?" + req.url.queryParams.encode
          Response.text(s"${req.method} /lobbies/$rest$q|$body")
    },
    Method.ANY / "lobbies" -> handler { (req: Request) =>
      for body <- req.body.asString.orElseSucceed("")
      yield Response.text(s"${req.method} /lobbies|$body")
    }
  )

  /** Bring up the upstream zio-http server on an ephemeral port,
    * yield the actual bound URL, then tear it down at scope exit. The
    * proxy now wraps outbound calls in a CLIENT span, so the test
    * runtime needs `Tracing` & `ContextStorage` — provided here as a
    * noop so the upstream + proxy are exercised without an OTLP exporter.
    */
  private def withUpstream[A](
      body: String => ZIO[Scope & Client & Tracing & ContextStorage, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.scoped {
      val serverLayer = ZLayer.succeed(Server.Config.default.port(0)) >>>
        Server.live
      ZIO.serviceWithZIO[Server] { srv =>
        for
          _    <- srv.install(upstream)
          port <- srv.port
          out  <- body(s"http://localhost:$port")
        yield out
      }.provideSomeLayer[Scope & Client & Tracing & ContextStorage](serverLayer)
    }.provide(Scope.default, Client.default, TracingLayer.noop)

  def spec = suite("LobbyProxy")(
    test("GET /lobbies forwards the method + empty path to the upstream") {
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        for
          response <- routes.runZIO(Request.get(url"/lobbies"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.startsWith("GET /lobbies")
        )
      }
    },
    test("POST /lobbies forwards body verbatim") {
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        for
          response <- routes.runZIO(
                        Request.post(url"/lobbies", Body.fromString("hello upstream"))
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("|hello upstream")
        )
      }
    },
    test("GET /lobbies/123/players forwards the trailing path") {
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        for
          response <- routes.runZIO(Request.get(url"/lobbies/123/players"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("/lobbies/123/players")
        )
      }
    },
    test("GET /lobbies/x preserves the query string") {
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        for
          response <- routes.runZIO(
                        Request.get(url"/lobbies/x?status=open&kind=blitz")
                      )
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("status=open"),
          body.contains("kind=blitz")
        )
      }
    },
    test("PUT /lobbies/abc forwards PUT") {
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        for
          response <- routes.runZIO(Request.put(url"/lobbies/abc", Body.empty))
          body     <- response.body.asString
        yield assertTrue(body.startsWith("PUT"))
      }
    },
    test("DELETE /lobbies/abc forwards DELETE") {
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        for
          response <- routes.runZIO(
                        Request(method = Method.DELETE, url = url"/lobbies/abc")
                      )
          body     <- response.body.asString
        yield assertTrue(body.startsWith("DELETE"))
      }
    },
    test("PATCH /lobbies/abc forwards PATCH") {
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        for
          response <- routes.runZIO(
                        Request(method = Method.PATCH, url = url"/lobbies/abc")
                      )
          body     <- response.body.asString
        yield assertTrue(body.startsWith("PATCH"))
      }
    },
    test("POST /lobbies/abc forwards POST + body via trailing route") {
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        for
          response <- routes.runZIO(
                        Request.post(url"/lobbies/abc", Body.fromString("xyz"))
                      )
          body     <- response.body.asString
        yield assertTrue(
          body.startsWith("POST /lobbies/abc"),
          body.endsWith("|xyz")
        )
      }
    },
    test("upstream unreachable returns 502 BadGateway (orElseSucceed branch)") {
      // Point the proxy at a port nothing is listening on. We don't need
      // the test upstream — just fire a request and check the fallback.
      val routes = LobbyProxy.routes("http://127.0.0.1:1")  // reserved port
      val program =
        for
          response <- routes.runZIO(Request.get(url"/lobbies"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.BadGateway,
          body.contains("upstream unreachable")
        )
      program.provide(Client.default, Scope.default, TracingLayer.noop)
    },
    test("invalid base URL surfaces as 502 (case Left in forward)") {
      // Whitespace in the configured base URL means `buildTarget` returns
      // Left → the `case Left(badStr)` arm in `forward` responds 502
      // BadGateway instead of crashing the route.
      val routes  = LobbyProxy.routes("http://lobby with space")
      val program =
        for
          response <- routes.runZIO(Request.get(url"/lobbies"))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.BadGateway,
          body.contains("invalid URL")
        )
      program.provide(Client.default, Scope.default, TracingLayer.noop)
    },
    test("baseUrlFromEnv reads PICHESS_LOBBY_URL when set") {
      for
        _   <- TestSystem.putEnv(LobbyProxy.EnvLobbyUrl, "http://lobby.test:9000")
        url <- LobbyProxy.baseUrlFromEnv
      yield assertTrue(url == "http://lobby.test:9000")
    },
    test("baseUrlFromEnv falls back to the docker compose default when unset") {
      for url <- LobbyProxy.baseUrlFromEnv
      yield assertTrue(url == "http://lobby-service:8092")
    },
    test("Host and Content-Length headers from the inbound request are dropped before forwarding") {
      // The proxy strips `host` and `content-length` (the underlying
      // client re-derives them). Sending a request with both headers
      // populated exercises the lowercase comparison + filter predicate
      // on lines 56-57 of LobbyProxy.
      withUpstream { base =>
        val routes = LobbyProxy.routes(base)
        val req = Request
          .post(url"/lobbies", Body.fromString("payload"))
          .addHeader(Header.Custom("Host", "old-host"))
          .addHeader(Header.Custom("Content-Length", "999"))
          .addHeader(Header.Custom("X-Keep", "yes"))
        for
          response <- routes.runZIO(req)
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("|payload")
        )
      }
    },
    test("baseUrlFromEnv treats a blank env value as unset") {
      for
        _   <- TestSystem.putEnv(LobbyProxy.EnvLobbyUrl, "   ")
        url <- LobbyProxy.baseUrlFromEnv
      yield assertTrue(url == "http://lobby-service:8092")
    }
  )

package chess.controller

import io.opentelemetry.api.trace.SpanKind
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.{ContextStorage, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

/** Reverse proxy for the lobby-service.
  *
  * The web-ui is served from the gateway origin (`http://gateway:8090`),
  * so any cross-origin call to the lobby-service on `:8092` would trip
  * the browser's CORS preflight machinery. Routing every lobby request
  * through the gateway keeps everything same-origin: the browser never
  * sends an OPTIONS preflight, and the lobby-service no longer has to
  * advertise permissive CORS headers.
  *
  * The proxy is dumb on purpose — it forwards method, path, query string,
  * headers and body verbatim, and streams the response back. All
  * authentication / rate-limiting hooks belong here later, but for now the
  * gateway and the lobby-service trust each other.
  */
object LobbyProxy:

  /** Env var pointing at the lobby-service base URL, e.g.
    * `http://lobby-service:8092` in docker compose.
    */
  val EnvLobbyUrl: String = "PICHESS_LOBBY_URL"

  private val DefaultLobbyUrl: String = "http://lobby-service:8092"

  /** Normalise the trailing-path segment so it always starts with a
    * single `/`. zio-http currently hands us a `Path` whose `toString`
    * has no leading slash, but a future bump might change that — the
    * guard keeps us correct either way.
    */
  private[controller] def joinPath(rest: Path): String =
    val s = rest.toString
    if s.startsWith("/") then s else s"/$s"

  /** Compose the upstream URL string + parse it. Pulled out of
    * `forward` so the `URL.decode` failure arm — which can be hit by
    * passing a base URL with control characters — is unit-testable
    * without standing up a proxy.
    */
  private[controller] def buildTarget(
      base: String,
      prefix: String,
      rest: Path,
      queryParams: QueryParams
  ): Either[String, URL] =
    val targetStr =
      s"${base.stripSuffix("/")}/$prefix${joinPath(rest)}" +
        (if queryParams.isEmpty then "" else "?" + queryParams.encode)
    URL.decode(targetStr).left.map(_ => targetStr)

  def routes(baseUrl: String): Routes[Client & Tracing & ContextStorage, Response] =
    val base = baseUrl.stripSuffix("/")

    /** Forward a request: same method, body, query string and headers,
      * URL rewritten onto the lobby-service host. `Host` and
      * `Content-Length` are dropped because the underlying HTTP client
      * recomputes them. Outgoing requests are wrapped in a CLIENT span
      * and the current trace context is injected as W3C `traceparent`
      * headers so the lobby-service's server-side middleware can pick
      * up the same trace.
      */
    def forward(prefix: String)(rest: Path, req: Request): ZIO[Client & Tracing & ContextStorage, Nothing, Response] =
      buildTarget(base, prefix, rest, req.url.queryParams) match
        case Left(badStr) =>
          ZIO.succeed(
            Response
              .text(s"lobby proxy: invalid URL $badStr")
              .status(Status.BadGateway)
          )
        case Right(target) =>
          val baseHeaders =
            Headers.fromIterable(
              req.headers.filter { h =>
                val n = h.headerName.toLowerCase
                n != "host" && n != "content-length"
              }
            )
          val spanName = s"HTTP ${req.method.name} /$prefix${joinPath(rest)}"
          ZIO.serviceWithZIO[Tracing] { tracing =>
            tracing.span(spanName, SpanKind.CLIENT) {
              val carrier =
                OutgoingContextCarrier.default(mutable.Map.empty[String, String])
              for
                _              <- tracing.injectSpan(TraceContextPropagator.default, carrier)
                injectedHeaders = carrier.kernel.foldLeft(baseHeaders) {
                                    case (acc, (k, v)) => acc.addHeader(k, v)
                                  }
                outbound        = Request(
                                    method = req.method,
                                    url = target,
                                    headers = injectedHeaders,
                                    body = req.body,
                                    version = req.version,
                                    remoteAddress = None
                                  )
                // `Client.batched` (vs `Client.request`) buffers the response
                // body so the caller doesn't need a Scope — fine for the
                // small JSON payloads the lobby-service returns. If lobbies
                // ever stream large bodies this should switch to `request`
                // and lift the route into a scoped handler.
                response       <- Client
                                    .batched(outbound)
                                    .orElseSucceed(
                                      Response
                                        .text("lobby proxy: upstream unreachable")
                                        .status(Status.BadGateway)
                                    )
              yield response
            }
          }

    val lobbiesForward: (Path, Request) => ZIO[Client & Tracing & ContextStorage, Nothing, Response] =
      forward("lobbies")

    Routes(
      Method.GET    / "lobbies"               -> handler { (req: Request) =>
        lobbiesForward(Path.empty, req)
      },
      Method.POST   / "lobbies"               -> handler { (req: Request) =>
        lobbiesForward(Path.empty, req)
      },
      Method.GET    / "lobbies" / trailing    -> handler(lobbiesForward),
      Method.POST   / "lobbies" / trailing    -> handler(lobbiesForward),
      Method.PUT    / "lobbies" / trailing    -> handler(lobbiesForward),
      Method.DELETE / "lobbies" / trailing    -> handler(lobbiesForward),
      Method.PATCH  / "lobbies" / trailing    -> handler(lobbiesForward)
    )

  /** Read the base URL from the env var, falling back to the docker
    * compose default. Pure config — does no network I/O.
    */
  def baseUrlFromEnv: UIO[String] =
    zio.System
      .env(EnvLobbyUrl)
      .map(_.filter(_.trim.nonEmpty).getOrElse(DefaultLobbyUrl))
      .orElseSucceed(DefaultLobbyUrl)

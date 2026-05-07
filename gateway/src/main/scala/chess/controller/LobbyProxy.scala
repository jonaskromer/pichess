package chess.controller

import zio.*
import zio.http.*

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

  def routes(baseUrl: String): Routes[Client, Response] =
    val base = baseUrl.stripSuffix("/")

    /** Build a forwarded request: same method, body, query string and
      * headers, with the URL rewritten onto the lobby-service host.
      * `Host` and `Content-Length` are dropped because the underlying
      * HTTP client recomputes them.
      */
    def forward(prefix: String)(rest: Path, req: Request): ZIO[Client, Nothing, Response] =
      val pathSuffix =
        val s = rest.toString
        if s.startsWith("/") then s else s"/$s"
      val targetStr =
        s"$base/$prefix$pathSuffix" +
          (if req.url.queryParams.isEmpty then ""
           else "?" + req.url.queryParams.encode)
      URL.decode(targetStr) match
        case Left(_) =>
          ZIO.succeed(
            Response
              .text(s"lobby proxy: invalid URL $targetStr")
              .status(Status.BadGateway)
          )
        case Right(target) =>
          val outboundHeaders =
            Headers.fromIterable(
              req.headers.filter { h =>
                val n = h.headerName.toLowerCase
                n != "host" && n != "content-length"
              }
            )
          val outbound =
            Request(
              method = req.method,
              url = target,
              headers = outboundHeaders,
              body = req.body,
              version = req.version,
              remoteAddress = None
            )
          // `Client.batched` (vs `Client.request`) buffers the response
          // body so the caller doesn't need a Scope — fine for the
          // small JSON payloads the lobby-service returns. If lobbies
          // ever stream large bodies this should switch to `request`
          // and lift the route into a scoped handler.
          Client
            .batched(outbound)
            .orElseSucceed(
              Response
                .text("lobby proxy: upstream unreachable")
                .status(Status.BadGateway)
            )

    val lobbiesForward: (Path, Request) => ZIO[Client, Nothing, Response] =
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

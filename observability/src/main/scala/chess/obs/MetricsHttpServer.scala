package chess.obs

import zio.*
import zio.http.*
import zio.metrics.connectors.prometheus.PrometheusPublisher

/** Tiny zio-http server that exposes the Prometheus exposition format on
  * `GET /metrics`. Each microservice forks this in its `Main` next to
  * the main listener (HTTP, gRPC, or Kafka-only) so Prometheus can
  * scrape every service uniformly.
  *
  * Ports are allocated statically per service so the Prometheus scrape
  * config under `docker/prometheus/prometheus.yml` can hard-code them:
  *
  *   gateway          → 9101
  *   game-service     → 9102
  *   repository       → 9103
  *   lobby-service    → 9104
  *   opening-service  → 9105
  *   analytics-service→ 9106
  *
  * The port is read from `METRICS_PORT` at boot, with the per-service
  * default supplied by the caller.
  */
object MetricsHttpServer:

  /** GET /metrics → Prometheus exposition text. Content-type stays at
    * `text/plain`; Prometheus parses either the legacy or the modern
    * exposition encoding from that value.
    */
  val routes: Routes[PrometheusPublisher, Response] =
    Routes(
      Method.GET / "metrics" -> handler {
        ZIO
          .serviceWithZIO[PrometheusPublisher](_.get)
          .map(text => Response.text(text))
      }
    )

  /** Read METRICS_PORT or fall back to the service-specific default.
    * Mirrors the env-handling style used elsewhere in the codebase
    * (see e.g. `chess.lobby.LobbyMain.portFromEnv`).
    */
  def portFromEnv(default: Int): UIO[Int] =
    zio.System
      .env("METRICS_PORT")
      .orDie
      .map(_.flatMap(_.toIntOption).getOrElse(default))

  /** Build a long-running effect that serves `/metrics` on `port`. The
    * intended call site is `.forkDaemon` so the metrics server lives
    * alongside the service's main listener without blocking it.
    *
    * Errors from the metrics server are logged and swallowed: a failed
    * metrics scrape must not kill the service it's measuring.
    */
  def serve(port: Int): URIO[Any, Unit] =
    Server
      .serve(routes)
      .provide(Server.defaultWithPort(port), MetricsLayer.live)
      .catchAllCause(c => ZIO.logErrorCause("metrics server failed", c))
      .unit

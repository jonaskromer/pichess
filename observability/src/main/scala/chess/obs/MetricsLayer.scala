package chess.obs

import zio.*
import zio.metrics.connectors.{MetricsConfig, prometheus}
import zio.metrics.connectors.prometheus.PrometheusPublisher

/** Composed ZLayer providing a Prometheus-backed `PrometheusPublisher`
  * service. Each service wires this into the metrics HTTP server that
  * [[MetricsHttpServer]] starts on its dedicated scrape port — see the
  * port allocation table in [[MetricsHttpServer.portFromEnv]].
  *
  * The publisher snapshots the ZIO runtime's metric registry (fiber
  * counts, JVM heap, request/response histograms, anything emitted by
  * application code via `zio.Metric`) every `interval` and renders the
  * Prometheus exposition format on demand.
  */
object MetricsLayer:

  /** Snapshot interval for the Prometheus publisher. 5s matches the
    * Prometheus scrape config under `docker/prometheus/prometheus.yml`.
    */
  val defaultInterval: Duration = 5.seconds

  /** Drop-in layer for service Mains. `ZLayer.make` wires the metrics
    * config in, the Prometheus connector (side-effecting — listens for
    * `zio.Metric` updates), and the publisher (which surfaces the
    * current registry as an exposition string) into a single
    * `ULayer[PrometheusPublisher]` so the call site is one line.
    */
  val live: ULayer[PrometheusPublisher] =
    ZLayer.make[PrometheusPublisher](
      ZLayer.succeed(MetricsConfig(defaultInterval)),
      prometheus.publisherLayer,
      prometheus.prometheusLayer,
    )

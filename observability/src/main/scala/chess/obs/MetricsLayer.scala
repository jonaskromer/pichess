package chess.obs

import zio.*
import zio.metrics.connectors.{MetricsConfig, prometheus}
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.metrics.jvm.{DefaultJvmMetrics, JvmMetricsSchedule}

/** Composed ZLayer providing a Prometheus-backed `PrometheusPublisher`
  * service. Each service wires this into the metrics HTTP server that
  * [[MetricsHttpServer]] starts on its dedicated scrape port — see the
  * port allocation table in [[MetricsHttpServer.portFromEnv]].
  *
  * The publisher snapshots the ZIO runtime's metric registry (fiber
  * counts, JVM heap + GC, request/response histograms, anything emitted
  * by application code via `zio.Metric`) every `interval` and renders
  * the Prometheus exposition format on demand.
  *
  * JVM-level metrics (`jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`,
  * thread counts, class loading, …) are activated separately via
  * [[jvmMetricsBootstrap]] in service Mains — the registry-update
  * trackers run as background fibers and only emit while alive, so they
  * need to be built on the long-running scope rather than the
  * MetricsHttpServer's short-lived request scope.
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

  /** Builds the JVM-metric trackers and returns a long-lived Scope-bound
    * unit. Call site idiom in each service Main:
    *
    * {{{
    *   ZIO.scoped {
    *     for
    *       _ <- chess.obs.MetricsLayer.jvmMetricsBootstrap
    *       _ <- MetricsHttpServer.serve(port).forkDaemon
    *       _ <- <main listener>
    *     yield ()
    *   }
    * }}}
    *
    * Building the layer once at startup wires the JVM trackers into
    * ZIO's metric registry; the publisher then sees them on every
    * subsequent scrape. `liveV2` is used (not `live`) because the v1
    * names are deprecated and emit `jvm_memory_bytes_used` instead of
    * the `jvm_memory_used_bytes` we expect the report generator to
    * parse.
    */
  val jvmMetricsBootstrap: ZIO[Scope, Throwable, Unit] =
    (JvmMetricsSchedule.default >>> DefaultJvmMetrics.liveV2).build.unit

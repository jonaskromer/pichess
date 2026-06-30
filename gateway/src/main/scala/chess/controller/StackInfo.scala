package chess.controller

import zio.*

/** Read-only identification of which persistence stack this gateway was
  * configured for at boot, plus the developer-tools toggle.
  *
  * Env vars (all set by the Makefile's `stack-*` targets):
  *   - `PICHESS_STACK` — active backend identifier (`postgres`, `mongo`,
  *     `cassandra`, `redis`, `inmemory`)
  *   - `PICHESS_STACK_EXTRAS` — comma-separated projection profiles (`opening`,
  *     `analytics`)
  *   - `PICHESS_DEV` — `"true"` activates the dev routes (under /dev/…) and
  *     surfaces the Dev link in the web-ui start screen
  *   - `PICHESS_GRAFANA_URL` / `PICHESS_PROMETHEUS_URL` — base URLs of the obs
  *     UIs, injected into the SPA shell so the Analytics screen's links resolve
  *     in both dev (localhost) and prod (the deploy's nip.io hosts)
  *
  * `backend` + `extras` are surfaced via `GET /api/stack-info` so the web-ui
  * can render an "Active stack" chip on its `/dev` index during perf-testing.
  * `devMode` gates the static-resource routes under /dev/… and is injected into
  * the SPA shell as a meta tag.
  */
final case class StackInfo(
    backend: String,
    extras: List[String],
    devMode: Boolean,
    grafanaUrl: String,
    prometheusUrl: String
)

object StackInfo:

  val EnvBackend: String = "PICHESS_STACK"
  val EnvExtras: String = "PICHESS_STACK_EXTRAS"
  val EnvDevMode: String = "PICHESS_DEV"
  val EnvGrafanaUrl: String = "PICHESS_GRAFANA_URL"
  val EnvPrometheusUrl: String = "PICHESS_PROMETHEUS_URL"

  // Dev defaults: the obs stack's localhost ports (`make … EXTRA=analytics,obs`).
  // Prod sets the public nip.io URLs via the full overlay's ConfigMap.
  val DefaultGrafanaUrl: String = "http://localhost:3000"
  val DefaultPrometheusUrl: String = "http://localhost:9090"

  val Default: StackInfo =
    StackInfo(
      backend = "inmemory",
      extras = Nil,
      devMode = false,
      grafanaUrl = DefaultGrafanaUrl,
      prometheusUrl = DefaultPrometheusUrl
    )

  /** Build a [[StackInfo]] from the current process environment. Missing /
    * blank env vars fall back to [[Default]].
    */
  val fromEnv: UIO[StackInfo] =
    for
      backendRaw <- zio.System.env(EnvBackend).orElseSucceed(None)
      extrasRaw <- zio.System.env(EnvExtras).orElseSucceed(None)
      devRaw <- zio.System.env(EnvDevMode).orElseSucceed(None)
      grafanaRaw <- zio.System.env(EnvGrafanaUrl).orElseSucceed(None)
      prometheusRaw <- zio.System.env(EnvPrometheusUrl).orElseSucceed(None)
    yield
      val backend =
        backendRaw.map(_.trim).filter(_.nonEmpty).getOrElse(Default.backend)
      val extras = extrasRaw
        .map(_.split(',').toList.map(_.trim).filter(_.nonEmpty))
        .getOrElse(Nil)
      val devMode = devRaw.map(_.trim.toLowerCase) match
        case Some("true") | Some("1") | Some("yes") | Some("on") => true
        case _                                                   => false
      val grafanaUrl = grafanaRaw
        .map(_.trim)
        .filter(_.nonEmpty)
        .getOrElse(Default.grafanaUrl)
      val prometheusUrl = prometheusRaw
        .map(_.trim)
        .filter(_.nonEmpty)
        .getOrElse(Default.prometheusUrl)
      StackInfo(backend, extras, devMode, grafanaUrl, prometheusUrl)

package chess.controller

import zio.*

/** Read-only identification of which persistence stack this gateway
  * was configured for at boot, plus the developer-tools toggle.
  *
  * Env vars (all set by the Makefile's `stack-*` targets):
  *   - `PICHESS_STACK`        — active backend identifier (`postgres`,
  *                              `mongo`, `cassandra`, `redis`,
  *                              `inmemory`)
  *   - `PICHESS_STACK_EXTRAS` — comma-separated projection profiles
  *                              (`opening`, `analytics`)
  *   - `PICHESS_DEV`          — `"true"` activates the dev routes
  *                              (under /dev/…) and surfaces the Dev
  *                              link in the web-ui start screen
  *
  * `backend` + `extras` are surfaced via `GET /api/stack-info` so the
  * web-ui can render an "Active stack" chip on its `/dev` index
  * during perf-testing. `devMode` gates the static-resource routes
  * under /dev/… and is injected into the SPA shell as a meta tag.
  */
final case class StackInfo(
    backend: String,
    extras: List[String],
    devMode: Boolean
)

object StackInfo:

  val EnvBackend: String = "PICHESS_STACK"
  val EnvExtras:  String = "PICHESS_STACK_EXTRAS"
  val EnvDevMode: String = "PICHESS_DEV"

  val Default: StackInfo =
    StackInfo(backend = "inmemory", extras = Nil, devMode = false)

  /** Build a [[StackInfo]] from the current process environment.
    * Missing / blank env vars fall back to [[Default]]. */
  val fromEnv: UIO[StackInfo] =
    for
      backendRaw <- zio.System.env(EnvBackend).orElseSucceed(None)
      extrasRaw  <- zio.System.env(EnvExtras).orElseSucceed(None)
      devRaw     <- zio.System.env(EnvDevMode).orElseSucceed(None)
    yield
      val backend = backendRaw.map(_.trim).filter(_.nonEmpty).getOrElse(Default.backend)
      val extras  = extrasRaw
        .map(_.split(',').toList.map(_.trim).filter(_.nonEmpty))
        .getOrElse(Nil)
      val devMode = devRaw.map(_.trim.toLowerCase) match
        case Some("true") | Some("1") | Some("yes") | Some("on") => true
        case _                                                   => false
      StackInfo(backend, extras, devMode)

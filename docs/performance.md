# Performance & Profiling Guide

> For the overall layer structure and package responsibilities, see [architecture.md](architecture.md). For the dev inner loop, see [development.md](development.md).

The piChess performance stack is six layers stacked under one harness, each
addressing a different question:

| Layer | Question it answers | Tool |
|---|---|---|
| 1. Load tests | "How does the service behave under N concurrent users?" | Gatling |
| 1b. Cross-stack load tests | "What can Gatling not see — browser, Kafka, gRPC?" | k6 (+ xk6-kafka, k6/browser) |
| 2. Microbenchmarks | "Which pure function is the hot path?" | JMH |
| 3. ZIO profiling | "Which effect is taking the time?" | zio-profiling (sampling) |
| 4. JVM profiling | "Which JIT-compiled frame is taking the time?" | async-profiler |
| 5. Metrics | "What's the live state of the running system?" | zio-metrics-connectors + Prometheus + Grafana |
| 6. Tracing | "Where in the request fan-out is the slow span?" | zio-opentelemetry + Jaeger |
| Harness | "Glue the above into a backend-comparison report." | `make perf` |

The entire stack is **opt-in**. Default `make up` stays lean; the observability
infrastructure (Prometheus / Grafana / Jaeger) ships under the `obs`
docker-compose profile.

---

## Quick start

Full cross-backend run with live observability, the way the `/dev/test/performance`
page assumes you'll use it:

```bash
TRACING_ENABLED=true make stack-postgres EXTRA=obs        # bring up obs + postgres
BACKENDS=inmemory,postgres,redis,mongo,cassandra \
  MODE=Stress OBS=true \
  make perf                                               # rotate stacks, run Gatling per backend
open http://localhost:3000/                                # Grafana — JVM dashboard
open http://localhost:9090/                                # Prometheus — raw query UI
open http://localhost:16686/                               # Jaeger — trace UI
```

After the harness completes, `perf-reports/<UTC-ts>/comparison.md` holds the
cross-backend table; each `perf-reports/<UTC-ts>/<backend>/` subdir holds the
per-backend Gatling HTML, the Prometheus baseline/final snapshots, and (when
profiling was on) the flame graphs.

---

## Architecture

```
                                        ┌──────────────────────────────┐
   docker-compose `obs` profile         │ obs-side containers          │
                                        │                              │
                                        │  ┌──────────┐  ┌──────────┐  │
                                        │  │Prometheus│  │ Grafana  │  │
                                        │  │   :9090  │  │  :3000   │  │
                                        │  └────┬─────┘  └────┬─────┘  │
                                        │       │ scrapes     │ reads  │
                                        │       │             │        │
   ┌──────────────────────┐  HTTP        │  ┌────▼─────────────▼───┐    │
   │ Gatling load runner  │──────────────┼─►│ /metrics on per-svc  │    │
   │ (sbt …/Gatling/test) │              │  │ port  9101..9106     │    │
   └──────────────────────┘              │  └──────────────────────┘    │
                                        │  ┌──────────┐                │
   ┌──────────────────────┐  OTLP gRPC   │  │  Jaeger  │  :16686       │
   │ TracingMiddleware    │──────────────┼─►│ all-in-1 │               │
   │ on each HTTP service │              │  └──────────┘                │
   └──────────────────────┘              └──────────────────────────────┘

   ┌──────────────────────┐  JFR dump on shutdown
   │   ProfilerLayer      │──────────► /var/log/pichess  ◄── bind-mount ──► ./perf-reports/profiles/
   │  (PICHESS_PROFILE)   │
   └──────────────────────┘

   ┌──────────────────────┐  attach by PID
   │   async-profiler     │──────────► /tmp/profile-*.html inside the container
   │  (scripts/profile-…) │            ──► docker cp → perf-reports/<ts>/<backend>/
   └──────────────────────┘
```

The microservices themselves don't know which observability tools are watching;
they just emit metrics, traces and (when profiling is on) samples. Add or
remove `Prometheus`/`Grafana`/`Jaeger` without touching any service code.

---

## Layer 1 — Gatling load tests

Module: `gatling/`. Built on Gatling 3.13.5 (Scala 3 native, Akka/Netty under
the hood). One simulation per load shape.

| Simulation | Shape | Default tuning | Use case |
|---|---|---|---|
| `GameSimulation`      | ramp users | 10 users / 5 s | Smoke check the gameplay path |
| `LobbySimulation`     | ramp users | 10 users / 5 s | Smoke check the lobby path |
| `StressSimulation`    | ramp to peak, then constant arrival rate | 50 peak / 5 s ramp / 60 s @ 5 rps | Sustained-load saturation |
| `EnduranceSimulation` | constant arrival rate | 5 rps / 60 s | Soak — surfaces leaks, GC creep, Kafka lag |
| `SpikeSimulation`     | trickle → burst × 3 | 2 rps + 3 × 50-user bursts | Resilience to sudden traffic spikes |
| `VolumeSimulation`    | ramp to high user count, each runs full flow | 50 peak / 5 s | Storage-layer stress (index growth, compaction) |
| `MixedSimulation`     | 70/30 game + lobby in parallel | derived from `pichessUsers` | Cross-service contention |

Common scenario fragments live in `gatling/src/test/scala/chess/gatling/Chains.scala`
so every simulation reuses the same gameplay / lobby chains.

### System properties (per-run tuning)

All shapes read these via `sys.props`; pass `-D<key>=<value>` to sbt:

| Property | Default | Used by |
|---|---|---|
| `pichessGatewayUrl`  | `http://localhost:8090` | every simulation |
| `pichessLobbyUrl`    | `http://localhost:8092` | lobby + mixed |
| `pichessUsers`       | `10`   | smoke simulations, mixed split |
| `pichessRampSeconds` | `5`    | every simulation |
| `pichessPeakUsers`   | `50`   | stress / spike / volume |
| `pichessHoldSeconds` | `60`   | stress (plateau) / endurance |
| `pichessRatePerSec`  | `5`    | stress (plateau) / endurance |

### Assertions

Every simulation calls `setUp(…).assertions(…)` with p95 / p99 / error-rate
SLAs (the values are loosened for stress / spike — see each simulation's
source). A breach turns the run RED in Gatling's HTML report and exits the
sbt task with a non-zero status, so a violated SLA fails CI.

### Invocation

```bash
sbt 'gatling/Gatling/test'                                                # all simulations
sbt 'gatling/Gatling/testOnly chess.gatling.StressSimulation'             # one
sbt -DpichessPeakUsers=200 -DpichessHoldSeconds=300 \
    'gatling/Gatling/testOnly chess.gatling.EnduranceSimulation'          # tuned
```

Output: `gatling/target/gatling/<simulation>-<runId>/`. The latest run is what
`make perf-bake` / `make gatling-build` pick up for the dev page.

---

## Layer 1b — k6 (surfaces Gatling can't reach)

Module: `k6/`. Image: pinned `grafana/k6:0.55.0` (Chromium + the `k6/browser`
module bundled in core since 0.50). One JS script per surface.

Layer 1 (Gatling) owns the HTTP load shapes. k6 sits next to it covering
the three surfaces Gatling can't speak natively:

| Surface | Script | What it measures | Status |
|---|---|---|---|
| Browser | `scripts/browser/lobby-flow.js` | Real Chromium → LCP / FCP / CLS on landing, `#new`, `#join` | **Shipped** |
| Kafka   | `scripts/kafka/game-events.js`  | Direct producer onto `pichess.game.events` (bypasses HTTP) | Deferred — needs xk6-kafka build |
| gRPC    | `scripts/grpc/game-service.js`  | Native gRPC against game-service (today only reached transitively via gateway) | Deferred |

### Why a second tool

The k6 integration is **additive**, not a replacement for Gatling. Gatling
already does HTTP load shapes / ramps / SLA-as-code well — see Layer 1.
What it can't do:

- **Render real pages.** Gatling speaks HTTP, not DOM — it can't measure
  LCP or FCP. The frontend's perceived performance is a complete blind
  spot in Layers 1–6.
- **Saturate Kafka directly.** `opening-service` and `analytics-service`
  are Kafka-only; the current Gatling sims reach them through the
  gateway → game-service → Kafka path, which makes the upstream the
  bottleneck before consumers are ever stressed.
- **Talk gRPC.** `game-service` is gRPC-only, so Gatling can't hit it
  directly — every measurement passes through the gateway.

### Shared infrastructure

| File | Purpose |
|---|---|
| `k6/lib/config.js`     | `cfg.gatewayUrl`, `cfg.vus`, … — single source for env-var fallbacks |
| `k6/lib/thresholds.js` | Shared SLA values. `httpThresholds` mirror Gatling assertions; `browserThresholds` use Google's "Good" Web Vitals buckets |
| `k6/Dockerfile`        | Single-stage from `grafana/k6:0.55.0`. Kafka surface will replace this with a `grafana/xk6` two-stage build |

### Compose / Docker

Service `k6` under the `k6` profile. Bind-mounts `k6/scripts:/scripts`,
`k6/lib:/lib`, `perf-reports:/out`. Runs with `network_mode: host` so
the same `localhost:8090` / `localhost:9092` / `localhost:8091` targets
resolve identically inside the container and from the host shell.

### Invocation

```bash
make stack-postgres EXTRA=obs                 # gateway must be reachable on :8090
make k6-build                                 # one-shot — pulls + builds the image
make k6-browser                               # runs k6/scripts/browser/lobby-flow.js
K6_VUS=20 K6_DURATION=120s make k6-browser    # tuned
```

`scripts/k6-run.sh` is the driver. It writes:

```
perf-reports/<UTC-ts>/k6/
└── browser/
    ├── summary.json   ← k6 handleSummary
    └── stdout.log     ← full run log
```

A threshold breach exits the surface's container non-zero; the driver
continues to the next surface and exits non-zero at the end if *any*
surface failed, so CI sees one pass/fail.

### Wiring to Layer 5 (Prometheus + Grafana)

k6 supports `--out experimental-prometheus-rw=…` for native remote-write
into the Prometheus container, and a dashboard JSON in
`docker/grafana/dashboards/k6.json` would be auto-provisioned the same
way as `pichess.json`. Neither is wired yet — first cut runs the script
standalone with the JSON summary; the metrics wiring lands when the
Kafka + gRPC surfaces do.

### Not yet wired

- `kafka` surface — needs an xk6-kafka image build and Kafka exposed
  on host port 9092 (currently internal-only).
- `gRPC` surface — needs game-service's gRPC port exposed and the
  `.proto` files mounted into the container.
- Backend rotation — `scripts/perf-run.sh` doesn't yet invoke k6 per
  backend. When folded in, output will move to
  `perf-reports/<ts>/<backend>/k6/` to match the Gatling layout.

---

## Layer 2 — JMH microbenchmarks

Module: `bench/`. Plugin: `sbt-jmh 0.4.7`. For nanosecond-scale measurement of
the pure-functional chess-engine internals that Gatling can't isolate.

| Bench class | Targets |
|---|---|
| `FenParserBenchmark`    | A/B/C of `FenParserRegex` / `FenParserCombinator` / `FenParserFastParse` |
| `FenSerializerBenchmark`| `FenSerializer.serialize` + `positionKey` |
| `GameApplyMoveBenchmark`| `Game.applyMove` over a single move + a 5-ply opening |
| `MoveValidatorBenchmark`| `isInCheck` + `hasLegalMove` at start and mid-game |
| `RayWalkBenchmark`      | `Ray.walk` over queen rays from a central square |
| `ZobristHashBenchmark`  | `Zobrist.hash` (NANOSECONDS — tight inner loop) |
| `SanRoundTripBenchmark` | parse-apply-serialize over a 16-ply Ruy Lopez |
| `PgnParserBenchmark`    | full-PGN parse over the curated corpus |

Shared inputs (FEN corpus, SAN sequences, PGN wrapper) live in
`bench/src/main/scala/chess/bench/BenchFixtures.scala`. ZIO effects are unwrapped
via `bench/src/main/scala/chess/bench/UnsafeRuntime.scala`'s shared default runtime.

JMH config is fixed in annotations: `Mode.AverageTime`, default in µs (ns for
Zobrist + Ray), `5 × 1s` warmup, `5 × 1s` measurement, `@Fork(1)`. Override on
the CLI when needed.

### Invocation

```bash
make bench                                                          # full suite, JSON to perf-reports/
sbt 'bench/Jmh/run -i 5 -wi 5 -f1 chess.bench.FenParserBenchmark'   # one class, default counts
sbt 'bench/Jmh/run -i 3 -wi 3 -f1 -rf json -rff bench-results.json' # all, JSON output
```

`make bench` writes `perf-reports/bench-<UTC-ts>.json` — the `make perf` harness
optionally folds it into its summary.

---

## Layer 3 — zio-profiling (sampling, fiber-aware)

Module: `observability/`. Library: `zio-profiling 0.3.3`. The marquee ZIO-native
piece of the stack — a CPU profiler that understands ZIO fibers, so output is
attributed to your effects rather than to `ZIO.evaluate` / `FiberRuntime`
frames.

The integration is in `chess.obs.ProfilerLayer.wrap(serviceName, program)`;
every service Main wraps its `run` body with it. With profiling disabled the
wrap is a literal pass-through (zero overhead).

### Modes

| `PICHESS_PROFILE` value | Behaviour |
|---|---|
| unset / anything else | Off. Pass-through wrap. |
| `sampling`            | Wraps the program in `SamplingProfiler(20ms).profile(…)`. On termination, dumps stack-collapsed format to `/var/log/pichess/profile-<service>-<UTC-ts>.folded`. |

The container path `/var/log/pichess/` is bind-mounted to the host's
`./perf-reports/profiles/` so dumps survive `docker stop` and the perf-matrix
backend rotation.

### Tagging compiler plugin (optional but recommended)

Without the plugin, the profile shows time spent in the ZIO evaluation loop —
useful for spotting CPU starvation but not for source-line attribution. With
the plugin, every effect-returning `def` / `val` is automatically tagged with
its source position, and the flame graph shows actual line-by-line attribution.

To build with the plugin (it's a compile-time decision):

```bash
PICHESS_PROFILE_BUILD=true sbt dockerBuildAll
```

`commonSettings` in `build.sbt` adds the plugin only when this env var is set,
and **also disables scoverage** for that build (the synthetic statements the
plugin emits would otherwise break the 100 % coverage gate). Profile builds and
CI builds are intentionally not the same artifact.

### Rendering a flame graph

The output is Brendan Gregg's stack-collapsed format:

```bash
git clone https://github.com/brendangregg/FlameGraph /tmp/FlameGraph
/tmp/FlameGraph/flamegraph.pl \
  perf-reports/profiles/profile-game-service-*.folded \
  > flame.svg
open flame.svg
```

---

## Layer 4 — async-profiler (sampling, JVM-wide)

Sampling CPU/alloc/lock/wall-clock profiler with no safepoint bias, low
overhead, prod-safe. Covers everything below ZIO — GC, JIT, native code — that
zio-profiling can't see. Use both: zio-profiling for "which effect", async-
profiler for "what is the JVM doing".

The integration is runtime-attach (no Java agent baked into the image), so
the same image runs in CI and under profiling without re-build.

### `scripts/profile-async.sh`

```bash
scripts/profile-async.sh SERVICE [DURATION] [EVENT] [OUT]
```

| Arg | Default | Notes |
|---|---|---|
| `SERVICE`  | required | docker-compose service name (`gateway`, `game-service`, …) |
| `DURATION` | `60`     | seconds to sample |
| `EVENT`    | `cpu`    | `cpu` / `alloc` / `lock` / `wall` |
| `OUT`      | `perf-reports/profiles/async-<event>-<service>-<ts>.html` | host-side output path |

Behaviour:
1. If `asprof` is already on the container's `PATH`, run it in-place.
2. Otherwise, copy `$ASPROF_BIN` (host-installed `asprof`) into the container
   at `/tmp/asprof` and run that. To install on macOS:
   `brew install async-profiler && export ASPROF_BIN=$(which asprof)`.
3. `docker compose cp` the resulting HTML flame graph out.

### Make targets

```bash
make profile-async-cpu   SERVICE=game-service DURATION=60
make profile-async-alloc SERVICE=game-service DURATION=60
```

Note on macOS: `asprof` runs inside the Linux container, not on the host.
Docker Desktop's VM has sane `perf_event_paranoid` defaults for arm64 Macs;
verify once with `docker compose exec game-service sysctl kernel.perf_event_paranoid`
if a first run fails.

---

## Layer 5 — Prometheus + Grafana metrics

Module: `observability/`. Library: `zio-metrics-connectors-prometheus 2.5.5`.
Wires the ZIO runtime's built-in `zio.Metric` registry (fiber counts, JVM heap,
GC pauses, etc.) plus any application-emitted counters/histograms into the
Prometheus exposition format.

### Wiring

`chess.obs.MetricsLayer.live` builds a single `ULayer[PrometheusPublisher]` from
the publisher + connector + `MetricsConfig(5.seconds)`.
`chess.obs.MetricsHttpServer.serve(port)` runs a tiny zio-http server that
exposes `GET /metrics` returning the latest snapshot.

Every service Main forks the metrics server on its dedicated port via
`MetricsHttpServer.serve(metricsPort).forkDaemon`:

| Service          | Metrics port | Override env  |
|------------------|--------------|---------------|
| gateway          | 9101         | `METRICS_PORT` |
| game-service     | 9102         | `METRICS_PORT` |
| repository       | 9103         | `METRICS_PORT` |
| lobby-service    | 9104         | `METRICS_PORT` |
| opening-service  | 9105         | `METRICS_PORT` |
| analytics-service| 9106         | `METRICS_PORT` |

For game-service (gRPC-only) and opening-service (Kafka-only), this is the only
HTTP listener.

### Prometheus

`docker/prometheus/prometheus.yml` hard-codes the six scrape targets at 5 s
intervals (matching `MetricsLayer.defaultInterval`). The Prometheus container
runs only under the `obs` profile.

### Grafana

`docker/grafana/provisioning/datasources/datasource.yml` auto-provisions
Prometheus as the default datasource at startup.
`docker/grafana/provisioning/dashboards/dashboards.yml` provisions every JSON
file in `docker/grafana/dashboards/` — currently just `pichess.json`, the
"piChess — JVM overview" dashboard (active fibers, JVM heap, GC pause time,
thread count). Anonymous read-only login is on (single-user dev rig).

### Invocation

```bash
make stack-postgres EXTRA=obs                    # adds prometheus + grafana + jaeger
curl http://localhost:9101/metrics | grep zio_   # sanity check the gateway scrape
open http://localhost:9090/                       # Prometheus query UI
open http://localhost:3000/                       # Grafana — default dashboard
```

Adding a new dashboard: drop a JSON file in `docker/grafana/dashboards/`,
restart the grafana container, the provisioner picks it up.

---

## Layer 6 — OpenTelemetry tracing

Module: `observability/`. Library: `zio-opentelemetry 3.0.0-RC24` +
`opentelemetry-sdk 1.43.0` + OTLP gRPC exporter.

`chess.obs.TracingLayer.fromEnv(name)` reads `TRACING_ENABLED`: when truthy it
builds the live SDK pointing at `OTEL_EXPORTER_OTLP_ENDPOINT`
(default `http://jaeger:4317`); otherwise it returns a noop layer that never
opens a connection. The choice is made at boot time so the live exporter
doesn't sit there retrying when Jaeger isn't around.

`chess.obs.TracingMiddleware.serverSpan` is a zio-http `HandlerAspect` that
extracts the incoming W3C `traceparent` / `tracestate` headers from each
request, starts a SERVER span named `<METHOD> <path>`, and ends it on response.

### Instrumented services

| Service           | Wiring | Span source |
|-------------------|--------|-------------|
| gateway           | Yes    | HTTP middleware on every `/api/*` and `/lobbies/*` route |
| lobby-service     | Yes    | HTTP middleware on every `/lobbies/*` route |
| repository        | Yes    | HTTP middleware on every `/games/*` route |
| analytics-service | Yes    | HTTP middleware on every `/analytics/*` route |
| game-service      | **No** — gRPC interceptor is deferred (see [Deferred work](#deferred-work)) |
| opening-service   | **No** — Kafka-only; consumer instrumentation is deferred |

What this means today: a request through the gateway shows as one trace at the
gateway, then a separate, unconnected trace at game-service / repository for
the downstream calls. Connecting them into a single trace is the deferred
gRPC / Kafka work.

### Jaeger

`jaegertracing/all-in-one:1.62.0` runs under the `obs` profile. UI on 16686,
OTLP gRPC receiver on 4317, OTLP HTTP receiver on 4318. 512 MB memory cap.

### Invocation

```bash
TRACING_ENABLED=true make stack-postgres EXTRA=obs
sbt 'gatling/Gatling/testOnly chess.gatling.GameSimulation'
open http://localhost:16686/                                # pick "gateway" from the service dropdown
```

---

## The harness — `make perf`

Module: `scripts/perf-run.sh` + `scripts/perf-summary.sh`. Orchestrates a
cross-backend run: rotate stacks, warm, run one Gatling simulation per backend,
snapshot metrics, bundle output, emit a markdown comparison table.

### Invocation

```bash
make perf                                                                          # defaults
BACKENDS=postgres,mongo,redis,cassandra MODE=Stress OBS=true make perf             # subset + obs
TRACING_ENABLED=true BACKENDS=postgres MODE=Mixed PEAK_USERS=200 \
  HOLD_SECONDS=120 OBS=true make perf                                              # tuned
```

### Env vars

| Var | Default | Notes |
|---|---|---|
| `BACKENDS`      | `inmemory,postgres,mongo,redis,cassandra` | comma-separated subset |
| `MODE`          | `Game` | Gatling simulation class name suffix: `Game` / `Lobby` / `Stress` / `Endurance` / `Spike` / `Volume` / `Mixed` |
| `OBS`           | `false` | when `true`, also activates `obs` profile and snapshots Prometheus before/after each run |
| `WARMUP_ITERS`  | `50` | game-replay warm-ups before measurement (so the JIT has compiled hot paths) |
| `PEAK_USERS`    | `50` | passes through to Gatling `-DpichessPeakUsers` |
| `RAMP_SECONDS`  | `10` | → `-DpichessRampSeconds` |
| `HOLD_SECONDS`  | `60` | → `-DpichessHoldSeconds` |
| `RATE_PER_SEC`  | `5`  | → `-DpichessRatePerSec` |

### Output layout

```
perf-reports/
└── 20260603T101500Z/
    ├── comparison.md                  ← markdown summary across all backends
    ├── postgres/
    │   ├── gatling/                    ← full Gatling HTML report tree
    │   ├── stats.json                  ← extracted from Gatling stats.json
    │   ├── summary.txt                 ← extracted key/value file used by comparison.md
    │   ├── prometheus-baseline.json    ← only when OBS=true
    │   ├── prometheus-final.json
    │   └── sbt.log                     ← raw sbt output for the Gatling run
    ├── mongo/   …
    ├── redis/   …
    ├── cassandra/   …
    └── inmemory/   …
```

`comparison.md` is what you commit / paste into a PR description / link from
the dev page after `make perf-bake`.

### Make targets

The perf suite is exposed as a single menu of Make targets — one per
layer or surface, plus an orchestrator for the full sweep.

**Run the full suite (Layers 1 + 1b + 2)**

| Target | What it does |
|---|---|
| `make perf-all` | JMH bench → Gatling cross-backend → all three k6 surfaces, in that order. Output rooted at one shared `perf-reports/<TS>/` tree. Honors every Layer 1 / 1b var below. Cleans the stack up via a trap, even on failure. |

**Run a single layer / surface**

| Target | Layer | What it does |
|---|---|---|
| `make perf`              | 1   | Cross-backend Gatling harness. Vars: `BACKENDS`, `MODE`, `OBS`, `PEAK_USERS`, … |
| `make k6-browser`        | 1b  | Real-Chromium flow against the gateway UI. Captures Core Web Vitals (LCP / FCP / CLS). Vars: `PICHESS_K6_VUS`, `PICHESS_K6_DURATION` |
| `make k6-kafka`          | 1b  | Direct `xk6-kafka` producer load onto `chess.game-events`. Bypasses the HTTP → gRPC → producer path so it stresses Kafka and downstream consumers directly. Needs `EXTRA=opening` (or `analytics`) on the stack so Kafka is up. |
| `make k6-grpc`           | 1b  | Native gRPC against game-service. Bypasses gateway + JSON entirely, so it isolates game-service's own command latency from the surrounding HTTP stack. Also surfaces a known game-service race under concurrent gameIds (see "k6/grpc state-match diagnostic" in the surface's stdout). |
| `make k6`                | 1b  | All k6 surfaces. Vars: `SURFACES` (default `browser` — set `SURFACES=browser,grpc,kafka` for the full set), `PICHESS_K6_VUS`, `PICHESS_K6_DURATION` |
| `make bench`             | 2   | JMH microbenchmark suite → `perf-reports/bench-<ts>.json` (no tunable env vars at the make-target level) |
| `make profile-async-cpu` SERVICE=… | 4   | Attach async-profiler (CPU) to a live service for `DURATION` seconds |
| `make profile-async-alloc` SERVICE=… | 4   | Same but for the `alloc` event |

Layers 3 (zio-profiling), 5 (Prometheus + Grafana), and 6 (OpenTelemetry
+ Jaeger) are env-driven rather than target-driven — see their sections
above for the `PICHESS_PROFILE` / `EXTRA=obs` / `TRACING_ENABLED` flags.

**One-shot setup + bake-in**

| Target | What it does |
|---|---|
| `make k6-build`             | Build the custom k6 image (one-shot — pulls `grafana/k6:0.55.0-with-browser`) |
| `make perf-summary`         | Regenerate `comparison.md` for the most recent perf run |
| `make perf-bake`            | Copy the most recent `perf-reports/<ts>/` into the gateway's dev resources |
| `make gatling-build`        | Legacy single-run alias — runs all simulations, bakes the latest into the gateway |

### Environment variables — full reference

Every tunable env var the perf-suite Make targets honor, grouped by
target. Defaults match the values in `scripts/perf-run.sh`,
`scripts/k6-run.sh`, `scripts/perf-all.sh`, and `k6/lib/config.js`.

**Layer 1 — `make perf` and `make perf-all`**

| Var | Default | What it controls |
|---|---|---|
| `BACKENDS`      | `inmemory,postgres,mongo,redis,cassandra` | Comma-separated subset of backends to rotate through. |
| `MODE`          | `Game` | Gatling simulation class name suffix: `Game` / `Lobby` / `Stress` / `Endurance` / `Spike` / `Volume` / `Mixed`. |
| `OBS`           | `false` (`perf`) / `true` (`perf-all`) | When `true`, also brings up `obs` profile (Prometheus + Grafana + Jaeger) and snapshots Prometheus before/after each run. |
| `WARMUP_ITERS`  | `50` | Game-replay warm-up cycles before measurement so the JIT has compiled hot paths. |
| `PEAK_USERS`    | `50` | → Gatling `-DpichessPeakUsers`. Used by stress / spike / volume / mixed simulations. |
| `RAMP_SECONDS`  | `10` | → Gatling `-DpichessRampSeconds`. Ramp duration. |
| `HOLD_SECONDS`  | `60` | → Gatling `-DpichessHoldSeconds`. Plateau / endurance hold window. |
| `RATE_PER_SEC`  | `5`  | → Gatling `-DpichessRatePerSec`. Open-loop arrival rate. |
| `PERF_TS`       | (auto, UTC stamp) | Override the run directory name. Set by `perf-all` so JMH + Gatling + k6 land in one tree; not normally set by hand. |

**Layer 1b — `make k6-*` and the k6 portion of `make perf-all`**

| Var | Default | What it controls |
|---|---|---|
| `SURFACES`            | `browser` (for `make k6`) | Comma-separated subset of `{browser,kafka,grpc}`. Other targets pin this. |
| `PICHESS_K6_VUS`      | `5`         | Virtual users per surface. **Not** `K6_VUS` — that's k6-reserved and would override the script's scenarios block and silently disable the browser type. |
| `PICHESS_K6_DURATION` | `30s`       | Per-surface max duration. **Not** `K6_DURATION` — same reasoning. |
| `K6_GATEWAY_URL`      | `http://localhost:8090` | Target for the browser surface. |
| `K6_LOBBY_URL`        | `http://localhost:8092` | Target for any lobby-specific browser steps (currently proxied via the gateway). |
| `K6_KAFKA_BROKERS`    | `localhost:29092` | Kafka bootstrap list for the kafka surface. The kafka service advertises `localhost:29092` on its `PLAINTEXT_HOST` listener so a `network_mode: host` k6 container can reach it (the internal `kafka:9092` is unresolvable from the host network namespace). |
| `K6_GRPC_TARGET`      | `localhost:9000` | game-service's gRPC target for the grpc surface. `game-service` already maps `9000:9000` on the host. |

**Layer 4 — `make profile-async-{cpu,alloc}`**

| Var | Default | What it controls |
|---|---|---|
| `SERVICE`  | (required) | Docker-compose service name to attach to: `gateway`, `game-service`, `repository`, `lobby-service`, `opening-service`, `analytics-service`. |
| `DURATION` | `60` | Sampling window in seconds. |

**Stack-level vars these targets implicitly read** (also documented in the README and `make help`):

| Var | Read by | What it does |
|---|---|---|
| `EXTRA`              | `make stack-*` (called from `perf-run.sh` per backend) | Compose profiles to layer on top — `obs`, `opening`, `analytics`. `perf-run.sh` sets this when `OBS=true`. |
| `TRACING_ENABLED`    | every service Main | When truthy, `chess.obs.TracingLayer` uses the live OTLP exporter to Jaeger; otherwise noop. |
| `PICHESS_PROFILE`    | every service Main | `sampling` enables zio-profiling; dumps land under `perf-reports/profiles/`. Requires a profile-tagged build (`PICHESS_PROFILE_BUILD=true sbt dockerBuildAll`) for source-line attribution. |

### k6 surface internals

All three k6 surfaces ship with scripts under `k6/scripts/<surface>/`
and run end-to-end via `make k6` / `make k6-{browser,grpc,kafka}`. The
infrastructure each surface relies on:

| Surface | Wiring |
|---|---|
| `make k6-browser` | Stock `grafana/k6:0.55.0-with-browser` ships Chromium; `K6_BROWSER_EXECUTABLE_PATH=/usr/lib/chromium/chromium` skips the failing default detection. The script walks the SPA's hash-routed screens (`/`, `/#new`, `/#join`) and captures Web Vitals via `k6/browser`. |
| `make k6-kafka` | Custom k6 binary built via `xk6 build --with xk6-kafka` (see `k6/Dockerfile`). Kafka advertises a `PLAINTEXT_HOST` listener at `localhost:29092` for the `network_mode: host` k6 container — the in-network `kafka:9092` doesn't resolve from the host network namespace. The script produces `GameDomainEvent`-shaped JSON onto `chess.game-events` and gates on `kafka_writer_write_seconds` + `kafka_writer_error_count`. |
| `make k6-grpc`  | k6 has native gRPC support (no extension). `./proto/src/main/protobuf` is bind-mounted at `/proto` so `client.load(['/proto/pichess'], 'game_service.proto')` resolves. game-service already maps `9000:9000` on the host. The script runs `NewGame → MakeMove × 8 → GetState`. The `state moveLog matches acked moves` check is intentionally non-gating — under concurrent gameIds, game-service ACKs MakeMove calls that don't always land in the per-game move stream; the script surfaces this rate as a diagnostic so it doesn't fail CI while the race is open. |

To run just one surface against an already-running stack:

```bash
# kafka surface needs Kafka up — opening profile is the smallest one that activates it
make stack-postgres EXTRA=opening
make k6-kafka

# grpc + browser only need the gateway + game-service
make stack-postgres
make k6-grpc
make k6-browser
```

---

## Dev page

`#dev/test/performance` (gated by `PICHESS_DEV=true`) renders three sections in
the existing paper-card aesthetic:

1. **Live observability links** — Grafana / Prometheus / Jaeger. They only
   resolve when the `obs` profile is active; otherwise the links 404. The page
   is intentionally a launcher, not a status board.
2. **How-to-reproduce** — a copy-friendly `make perf BACKENDS=… MODE=…`
   snippet so the URL of a Gatling report is bundled with the command that
   regenerates it.
3. **Latest baked report** — iframe of `/dev/performance/report/`, refreshed
   by `make perf-bake` (or the legacy `make gatling-build`).

The page is **read-only**: no buttons that kick off runs. The reasoning
(self-DoS surface + Docker-socket privilege + image bloat) is settled — see the
discussion in [ADR](adr/) follow-ups, or rerun via the CLI.

---

## Memory budget — MacBook Air notes

The dev rig is a 16 GiB MacBook Air. Approximate per-container RSS during a
perf run, so you can decide what's safe to bring up together:

| Container | Approx | When it's up |
|---|---|---|
| Each microservice (×7)        | 600 MB–1 GiB | always |
| Active backend (postgres / redis / mongo) | 300–500 MB | active `stack-*` |
| Cassandra (when active)       | ~1 GiB     | `stack-cassandra` |
| Kafka                          | ~700 MB    | with `EXTRA=opening` or `EXTRA=analytics` |
| ClickHouse                     | ~4 GiB     | with `EXTRA=analytics` |
| Prometheus                     | ~250 MB    | `EXTRA=obs` |
| Grafana                        | ~250 MB    | `EXTRA=obs` |
| Jaeger all-in-one              | ~500 MB    | `EXTRA=obs` |
| Gatling load generator (sbt)   | ~500 MB    | during a perf run |

Rules of thumb:
- `obs + postgres / mongo / redis` is comfortable.
- `obs + analytics` adds ~5 GiB (ClickHouse + Kafka); doable but tight.
- `obs + cassandra` is borderline; consider dropping `obs` for cassandra runs.
- For the broadest matrix (`BACKENDS=…all 5… MODE=Stress OBS=true`), expect
  to be near 12 GiB allocated. Close the browser and the IDE first.

---

## Wire-format conventions for gRPC payloads

When a gRPC response carries a rich, structured payload (not just a few
strings/ids) and the producer + consumer are both Scala microservices in
this monorepo, the convention is to **ship the wire payload as a single
`bytes` field carrying a Scala case-class DTO encoded via boopickle**.
The DTO lives in the shared `api` module — same type at both ends, no
proto↔Scala translation layer.

Current production user: `StateReply.board_state` carries a
`BoardStateDto` encoded by `BoardStateDto.encodeBytes` (boopickle) and
decoded by `BoardStateDto.decodeBytes`. The gateway's
`WebController.replyToDto` consumes the bytes directly; the gateway no
longer parses a FEN string out of every reply. Sidecar `fen` field
remains for the annotation cache, which needs castling + en-passant
info the `BoardStateDto` doesn't carry.

**Codec choice rule:**

| Codec | Round-trip on `BoardStateDto` (64 squares) | Verdict |
|---|---|---|
| **boopickle** (`io.suzaku::boopickle:1.5.0`) | ~12 µs (ties FEN) | **use this** |
| zio-json (text) | ~18 µs | acceptable fallback |
| FEN string (hand-tuned) | ~12 µs | retained for `fen` sidecar + persistence |
| zio-schema-protobuf | **~416 µs (33× slower)** | **do not use on hot paths** |

The zio-schema-protobuf trap was found the hard way: an initial
migration regressed end-to-end p99 by ~7× under high load.
Redis persistence still uses zio-schema-protobuf via
`RedisLayers.ProtobufCodecSupplier` and that's fine because the
`GameState` payload is smaller and Redis isn't a per-request hot
path — but don't extend that pattern to gRPC or any per-request
encode site. The microbench in
`bench/.../BoardStateDtoBenchmark.scala` pins the numbers down across
small + medium DTO sizes and is the place to add a new codec
contender before any migration.

**When the pattern doesn't apply.** If the response is already a few
flat strings (e.g. `ExportReply { format, body }` where `body` is
rendered to its final wire format at the source), there's no Scala
DTO middle layer to eliminate — keep the structured proto fields. The
pattern is for collapsing a proto↔Scala translation step, not for
binary-encoding-as-a-virtue.

---

## Deferred work

The plan in [the design ADR](adr/) called for additional tracing surfaces; they
weren't required to ship the rest of the stack and have known integration
risk. All three slot in additively (no rework of what's shipped).

| Deferred | Wiring needed | Reasoning |
|---|---|---|
| **gRPC tracing** between gateway ↔ game-service | `ZServerInterceptor.fromServerInterceptor(otelInterceptor)` at `GameServiceMain.scala:79` + matching `ManagedChannelBuilder.intercept(...)` at `GatewayMain.scala:86`. Needs the `opentelemetry-grpc-1.6` instrumentation jar. | Today a gateway→game-service request shows as two unconnected traces. Adding this is the single highest-value tracing improvement. |
| **Kafka context propagation** | Wrap `producer.produce(...)` in `Tracing.span("kafka.produce")` with header injection at `KafkaGameEventProducer.scala:21`, and header extraction at the three consumers (`repository/KafkaGameEventConsumer.scala:43-63`, `opening/.../KafkaOpeningConsumer.scala`, `analytics/.../KafkaAnalyticsConsumer.scala`). | zio-kafka 2.10 has no native OTel hook; this is a small amount of manual plumbing. |
| **DB tracing decorator** | New `persistence/tracing/` module mirroring `persistence/cache/.../CachedGameRepository.scala:19-38`, wrapping `GameRepository` and `LobbyRepository` at the trait boundary so every backend gets `db.<op>` spans uniformly. Wire at `PersistenceLayers.gameRepository`. | Single point-of-instrumentation gives identical span shape across Postgres / Mongo / Redis / Cassandra. |

The observability module already pulls in `zio-opentelemetry` and the
`opentelemetry-sdk` so all three land as new files + a few-line wiring
change, with no dependency churn.

---

## Where things live

| File / dir | Purpose |
|---|---|
| `gatling/`                                              | Gatling simulations + `Chains` + `SharedConfig` |
| `k6/`                                                   | k6 scripts (browser / kafka / grpc) + `lib/` shared config + thresholds + `Dockerfile` |
| `bench/`                                                | JMH benchmarks + `BenchFixtures` + `UnsafeRuntime` |
| `observability/`                                        | Cross-cutting layers: `MetricsLayer`, `MetricsHttpServer`, `ProfilerLayer`, `TracingLayer`, `TracingMiddleware` |
| `scripts/perf-all.sh`                                   | Full-suite orchestrator — drives `make perf-all` |
| `scripts/perf-run.sh`                                   | Backend-comparison harness |
| `scripts/perf-summary.sh`                               | `comparison.md` generator |
| `scripts/k6-run.sh`                                     | k6 surface driver (browser / kafka / grpc) |
| `scripts/profile-async.sh`                              | async-profiler attach driver |
| `docker/prometheus/prometheus.yml`                      | Scrape config (six static targets) |
| `docker/grafana/provisioning/`                          | Auto-provisioned datasource + dashboards-from-dir provider |
| `docker/grafana/dashboards/pichess.json`                | Default "piChess — JVM overview" dashboard |
| `perf-reports/`                                         | Output directory (gitignored). One subdir per `make perf` run + `profile/` for `PICHESS_PROFILE=sampling` dumps |
| `gateway/src/main/resources/dev/performance/report/`    | Static artifact directory the dev page iframes — baked by `make perf-bake` or `make gatling-build` |

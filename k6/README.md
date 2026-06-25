# k6 — surfaces Gatling can't reach

Layer 1b of the piChess perf stack (see [`docs/performance.md`](../docs/performance.md)).
Gatling owns the HTTP load shapes; k6 covers what Gatling can't:

| Surface | Script | Why k6, not Gatling |
|---|---|---|
| Browser  | `scripts/browser/lobby-flow.js`   | Real Chromium → Core Web Vitals (LCP/FCP/CLS). Gatling only sees HTTP. |
| Kafka    | `scripts/kafka/game-events.js`    | Direct producer load via `xk6-kafka`. Gatling can only reach Kafka via the upstream HTTP path. |
| gRPC     | `scripts/grpc/game-service.js`    | Native gRPC against game-service. Today only reachable transitively through the gateway. |

All three surfaces now ship — run them with `make k6-browser`, `make k6-kafka`,
and `make k6-grpc` (see Quick start).

## Quick start

```bash
make stack-postgres EXTRA=obs          # gateway must be reachable on :8090
make k6-build                          # build the custom k6 image (one-shot)
make k6-browser                        # run the browser flow
```

Output lands in `perf-reports/<UTC-ts>/k6/browser/`:
- `summary.json`  — k6's machine-readable run summary
- `stdout.log`    — full run log
- thresholds breach → non-zero exit, same CI gating as Gatling assertions

## Config

Every script reads its target URLs from env (`lib/config.js`):

| Env | Default | Used by |
|---|---|---|
| `K6_GATEWAY_URL`    | `http://localhost:8090` | browser, grpc (web target) |
| `K6_LOBBY_URL`      | `http://localhost:8092` | browser (lobby flow) |
| `K6_KAFKA_BROKERS`  | `localhost:29092`       | kafka — the host-side `PLAINTEXT_HOST` listener |
| `K6_GRPC_TARGET`    | `localhost:9000`        | grpc — game-service's host-mapped port |
| `PICHESS_K6_VUS`      | `5`                   | all (concurrent virtual users) |
| `PICHESS_K6_DURATION` | `30s`                 | all |

The load-shape vars are deliberately `PICHESS_`-prefixed: the bare `K6_VUS` /
`K6_DURATION` are k6-reserved and would force a default `constant-vus` scenario,
overriding each script's own `scenarios: { … }` block (and silently disabling
the browser type for the lobby flow). The URL / broker / target vars keep their
plain `K6_` names — only VUs and duration collide.

Thresholds (SLA assertions) live in `lib/thresholds.js` so every surface
shares the same pass/fail criteria.

Inside the container the lib dir lives at `/k6lib/` (not `/lib/` — that
would shadow the musl dynamic linker and break Chromium). Scripts
import via `import { cfg } from '/k6lib/config.js'`.

# 013 — Deletion of `app` module; `sbt run` no longer wired

## Status
Accepted (Phase 5 / 11 re-architecture).

> **Update (TUI runtime landed):** the closing Consequence below — "the `tui`
> module is now a parser-only library; runtime … is documented future work" — is
> no longer true. The TUI now has a full runtime: `TuiMain` (a `ZIOAppDefault`,
> and the module's wired `mainClass`) drives `TuiClient`, a typed REST client over
> the **shared `chess.api.Endpoints`** (the same contract the gateway serves and
> the web-ui consumes), with live updates via `TuiEventStream` (SSE on
> `/api/events`) and a lobby flow through the gateway's `/lobbies/*` reverse
> proxy. The `app`-deletion and `sbt run`-unwired decisions below still stand.

## Context
Until Phase 11, the `app` SBT module was the composition root: a single Main (`chess.Main`) wired TUI + gateway + game-service in one process, with the repository reachable over a synchronous REST PUT toggled by the `REPOSITORY_URL` env var. The root `build.sbt` aliased `run` to `app/Compile/run`, so `sbt run` started "the whole thing".

Phase 11 splits the monolith into three independently deployable services: gateway (HTTP/SSE), game-service (gRPC, Kafka producer), repository (REST + Kafka consumer). There is no longer a single "the whole thing" Main.

## Decision
- **Delete the `app` module.** The directory `app/`, the `chess.Main` orchestrator, `selectRepositoryLayer`, the `REPOSITORY_URL` switch, and the `run := (app / Compile / run).evaluated` alias all go.
- **`sbt run` is intentionally unwired.** Typing `sbt run` at the root errors with "No main class detected" — that is the signal to use one of the supported workflows instead.

## Replacement workflows
1. **Integrated (Docker, prod-shaped):** `./scripts/dev-up.sh` → builds and starts kafka, game-service, repository, gateway.
2. **Single-service rebuild:** `./scripts/dev-up.sh gateway` (or `game-service` / `repository`). Rebuilds only that image and restarts the container. `--no-deps` keeps everything else running.
3. **Tightest inner loop (host JVM):** Run Kafka in compose, run touched service via `sbt <svc>/run` on the host with `KAFKA_BOOTSTRAP_SERVERS=localhost:9092` etc.

## Why not keep a thin orchestrator
We considered a small `dev` module that boots all three Mains in one JVM with embedded-kafka. It would be brittle (tightly coupled to Kafka client internals, hard to keep in sync with prod), would diverge from the actual deploy story, and would slow startup. Better to make the prod-shaped Docker workflow be the default than to fake a monolith we no longer have.

## Consequences
- Existing tutorial material that says "just `sbt run`" is wrong — `README.md` and `docs/development.md` updated accordingly.
- Any user reaching for a one-command boot uses `./scripts/dev-up.sh`, which is the actual prod-shaped path.
- The `tui` module is now a parser-only library; runtime (REST client to the gateway) is documented future work. This is a tradeoff: TUI runnability lost in this iteration, simpler module graph gained.

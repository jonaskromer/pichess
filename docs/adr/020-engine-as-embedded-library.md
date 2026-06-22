# ADR 020 — Engine as an embedded library, not a service

## Status

Accepted

## Context

Three consumers need to pick moves: `game-service` (play-vs-computer),
`bot-lichess`, and `bot-tournament` ([ADR 015](015-tournament-server-integration-module.md)).
The "enterprise" shapes — a dedicated engine microservice, or shelling out to an
external UCI engine (Stockfish) — each add a process, a protocol, and a failure
mode. The deployment stance is also local-and-committable: no runtime external
APIs, no giant data files.

## Decision

`bot-engine` is a **pure library, linked in-process** and called by plain function.

- It is a plain sbt module (`build.sbt:226-228`, "pure CPU search + evaluator …
  no DB / no network / no DTOs") that `game-service`, `bot-lichess`, and
  `bot-tournament` each `.dependsOn` (`:603`, `:244`, `:265`). There is **no**
  engine service: the seven built images are repository / game-service /
  lobby-service / opening-service / analytics-service / gateway / tui — no engine
  among them.
- `game-service` plays vs-bot by a direct call
  `search.bestMove(state, depth, …)` (`VsBotOrchestrator.scala:118`,
  `GrpcServer.scala:210`) — not gRPC/HTTP/UCI.
- **Weights load from classpath resources.** `EngineBundle.fromResources`
  (`EngineBundle.scala:78`) → `WeightsLoader` `getResourceAsStream` (`:54`); the
  committed nets live in `bot-engine/src/main/resources/` (`nnue-v1.bin` ≈ 193 KB,
  plus the ensemble nets, HCE JSON weights, and opening PGN).
- **No runtime tablebase/API in the engine.** `EngineBundle` defaults
  `tablebaseOracle = None` (`:86`); game-service and bot-tournament wire none. The
  single online oracle — Lichess's 7-piece Syzygy HTTP API
  (`LichessTablebaseSearch.scala:14`) — is added **only by bot-lichess** and is
  **opt-out / default on** there (`LICHESS_TABLEBASE=false` disables,
  `LichessBotMain.scala:69-71`). No local Syzygy files are committed.

(See `docs/bot.md:23-25,85-86`: "`game-service` links it … `bot-lichess` links it
… `bot-tournament` links it"; weights via `EngineBundle.fromResources`.)

## Consequences

**Benefits:**
- No engine service and no UCI subprocess: no extra deployment, no network hop on
  the move's hot path, no client/contract drift. A move is a function call.
- Local and committable end-to-end — the nets are hundreds of KB on the classpath,
  reproducible, with no runtime external dependency (the Lichess oracle is the
  only network call, and only inside the Lichess bot).

**Trade-offs:**
- The engine + NNUE weights ship **inside every consumer image/JVM**, so a weight
  change rebuilds three images and they can momentarily run different versions.
  Accepted: the nets are small and versioned (`nnue-vN.bin`), and the engine is
  CPU-pure, so co-location is cheap.
- No independent scaling of "search" as its own tier; search load rides with each
  consumer. Fine at current scale.

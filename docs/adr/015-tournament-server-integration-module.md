# ADR 015 — Tournament play as a separate `bot-tournament` module

## Status

Accepted

## Context

The same engine that plays on Lichess (`bot-lichess`) should also play in
external **NowChess** tournaments (an HTWG-hosted tournament server). That server
speaks a different protocol than Lichess: its own REST + Server-Sent-Event
streams, UCI moves, **clocks in seconds** (not millis), and a broadcast
`gameStart` event that fires for *both* colours of *every* game in the tournament.

Options:

1. **Extend `bot-lichess`** with a second protocol behind a flag.
2. **A new isolated module** that reuses the engine core but not the Lichess
   transport.

The engine core (search, eval, time management) is protocol-agnostic; only the
transport and the clock/colour bookkeeping differ. Bolting a second protocol onto
the Lichess client would entangle two unrelated event models.

## Decision

A new **`bot-tournament`** sbt module (package `chess.bot.tournament`,
`name := "pichess-bot-tournament"`), depending on
`domain / rules / codec / bot-engine` — the **same engine core** as `bot-lichess`,
a **different transport**. Shape:

- `TournamentApiClient` — register (`{name, isBot:true}` → `{id, token}`, bearer
  kept in a `Ref`), list/join tournaments, and `POST …/move/{uci}` (2xx ok,
  4xx — incl. 409 — error).
- `TournamentBridge` — subscribes to the tournament + per-game event streams;
  **self-filters** the broadcast `gameStart` by matching the registered bot id
  against the game's `white` / `black` (dedup by gameId); converts the
  seconds-based clock (+ increment carried from the tournament object) to the
  engine's millis time budget; per-game reconnect (`retry(Schedule.fixed(5s))`),
  never-resign.
- `TournamentRunner` — parses the position straight from each sync's `fen` (no
  move replay) and picks a move with the shared engine.
- `TournamentBotMain` (`ZIOAppDefault`) — entrypoint; config via `TOURNAMENT_*`
  env vars (`TOURNAMENT_BASE_URL`, `TOURNAMENT_ID`, `TOURNAMENT_BOT_NAME`,
  `TOURNAMENT_WEIGHTS_VERSION`, `TOURNAMENT_MOVE_DEPTH`, `TOURNAMENT_LAZYSMP`);
  run with `sbt 'botTournament/run'`.

Deliberately **no Syzygy tablebase oracle** on the tournament host (unlike
`bot-lichess`), and never-resign. See [tournament-integration.md](../tournament-integration.md)
for the verified wire formats and open operational items.

## Consequences

**Benefits:**
- The Lichess client is untouched; the engine core is **shared, not forked**.
- The tournament protocol is fully isolated and unit-tested without a live
  server (`TournamentApiClientSpec`, `TournamentBridgeSpec`,
  `TournamentRunnerSpec`, `TournamentEventCodecSpec`).

**Trade-offs:**
- A second client to maintain.
- A few small codec helpers (module-local `internal.SyncCodec`) are copies rather
  than shared with `bot-lichess`'s equivalent — accepted to keep the modules
  decoupled.
- Not yet containerised (run from sbt), and a handful of server-side details
  remain externally-gated — tracked in `tournament-integration.md`.

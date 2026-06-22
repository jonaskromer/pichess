# ADR 014 — Spectator presence lives in the gateway, not game-service

## Status

Accepted

## Context

Lobbies can host **public** games that other people may watch. We want, per game:
a live spectator count ("N watching"), an enforceable policy (is spectating
allowed? cap the number of watchers?), and a way to surface running games to
spectate — without complicating the authoritative game engine.

`game-service` owns authoritative game state (a per-game
`SubscriptionRef[SessionState]`, reached over gRPC — see [ADR 006](006-subscriptionref-sse-for-ui-sync.md),
[ADR 011](011-grpc-for-internal-rpc.md)). But "who is watching" is about who is
connected to the **gateway's SSE feed**, not about the game itself.

Options:

1. **Track spectators in game-service**, alongside the session. Couples a pure
   transport concern into the engine; every gRPC client would have to report and
   withdraw presence, and a gateway crash would leave phantom watchers in the
   authoritative state.
2. **Track them in the gateway**, where the SSE connections actually live and die.

## Decision

A gateway-only `SpectatorPresence` (`gateway/.../controller/SpectatorPresence.scala`)
holds, per game id, a `SubscriptionRef[Int]` (the live count) and a
`SpectatorPolicy(allowSpectate, limit)`. game-service stays oblivious.

- **Presence is derived purely from open SSE connections.** A
  `GET /api/games/{id}/events?role=spectator` stream calls `admit`, which
  **atomically** checks-and-seats under the policy: refuse when spectating is
  disallowed (`not-allowed`) or the cap is reached (`full`); otherwise occupy a
  slot for the stream's `Scope` and release it (decrement) on disconnect.
  Players (the Game screen) read the count but never occupy a slot.
- **Every viewer subscribes to the count.** `changes` is merged into each
  viewer's SSE feed as `spectators` events; a refused spectator gets a single
  `spectator-denied` event (data = the rejection code) and no board.
- **Policy is handed over by the lobby**, not invented by the gateway: when a
  hosted game starts, the lobby calls `POST /internal/games/{id}/players` whose
  `RegisterPlayersRequest` now carries `allowSpectate` + `spectatorLimit`
  (threaded through `GatewayCoordinator.registerPlayers`).
- **The public-lobby browser** is the discovery half: `LobbyRepository`'s
  `listPublicWaiting()` was renamed `listPublicActive()` and broadened to return
  every `Public` lobby that isn't `Closed` (i.e. `Waiting` / `Full` / `Started`),
  so the web-ui surfaces *running* games to spectate, not only open seats.

This extends [ADR 006](006-subscriptionref-sse-for-ui-sync.md)'s
SubscriptionRef-over-SSE pattern to a second, count-only stream.

## Consequences

**Benefits:**
- The engine / game-service is unchanged and unaware of spectators — spectating
  is purely a transport concern in the tier that owns the connections.
- Presence resets *correctly* on a gateway restart: the counts it was tracking
  are gone exactly because the SSE connections it was counting are gone too.
- The cap is race-free — the check-and-seat is an atomic `modify` on the count
  ref, so two simultaneous joins can't both slip past a limit of N.
- Zero extra persistence; nothing to migrate or clean up.

**Trade-offs:**
- Presence is in-memory and **per-gateway-instance**: horizontally scaling the
  gateway would split the count. Acceptable — a single gateway today.
- The map grows one entry per game ever watched over the process lifetime,
  mirroring game-service's own in-memory session map. Bounded by a restart.

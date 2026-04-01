# ADR 006 — SubscriptionRef + SSE for TUI/GUI synchronization

## Status

Accepted

## Context

The application has two UIs — a terminal TUI and a browser-based web GUI — that must stay in sync: a move made in either UI should immediately appear in the other.

Options considered:

1. **Observer pattern** — register listeners on a shared model; notify on change. Classic GoF approach.
2. **Polling** — the web GUI periodically fetches `/api/state`. Simple but introduces latency and wasted requests.
3. **WebSockets** — full-duplex channel between server and browser. Powerful but complex to manage (connection lifecycle, reconnection, protocol).
4. **SubscriptionRef + SSE** — `SubscriptionRef[SessionState]` provides a `Ref` (atomic read/write) that also exposes a `.changes` ZStream. Server-Sent Events stream those changes to the browser. The TUI races `readLine` against the same `.changes` stream.

## Decision

Use `zio.stream.SubscriptionRef[SessionState]` as the single source of truth, with SSE for browser push.

- **Shared state:** Both UIs read from and write to the same `SubscriptionRef`. Any update is immediately visible to all subscribers.
- **Web → browser:** An `/api/events` SSE endpoint pipes `session.changes` into `ServerSentEvent` frames via `Response.fromServerSentEvents`. The browser listens with `EventSource`.
- **TUI reactivity:** The TUI loop races `readLine` against `session.changes.drop(1).take(1)`. If the web GUI makes a move while the TUI is waiting for input, the race resolves to `ExternalChange` and the TUI re-renders immediately.
- **Shutdown propagation:** A `quit` event type on the SSE stream tells the browser to show the goodbye screen, regardless of which UI initiated the quit.

```scala
// TUI: three-way race
readLine.map(Input(_))
  .race(session.changes.drop(1).take(1).runHead.as(ExternalChange))
  .race(shutdown.await.as(Shutdown))
```

## Consequences

**Benefits:**
- No custom observer infrastructure. `SubscriptionRef` is a standard ZIO primitive — thread-safe, backpressure-aware, and scope-managed.
- SSE is simpler than WebSockets for server-to-client push (no handshake protocol, automatic browser reconnection, works through proxies).
- The TUI reacts to external changes without polling or busy-waiting — the `.changes` stream wakes it up.
- Adding a third UI (e.g. a mobile client) requires only subscribing to the same `SubscriptionRef.changes` stream.

**Trade-offs:**
- SSE is unidirectional (server → client). The browser still uses `POST` requests to send moves. This is acceptable because moves are infrequent and request/response semantics suit them.
- `readLine` is a blocking call. When the race resolves to `ExternalChange`, the blocked `readLine` fiber is interrupted. On some terminals this may leave stale input in the buffer. In practice this has not been an issue.
- The TUI re-renders the full board on every external change, even if the change is minor (e.g. an error message). Acceptable for a text-based UI.

# ADR 007 — Promise for coordinated shutdown

## Status

Accepted

## Context

The application runs three concurrent components: an HTTP server, a TUI loop, and SSE streams to browser clients. Quitting from any UI must cleanly shut down all components and show a goodbye message in both UIs.

Options considered:

1. **`ZIO.interrupt`** — interrupt the TUI fiber; `zipPar` propagates interruption to siblings. Simple, but produces noisy `InterruptedException` log output and gives no opportunity for graceful goodbye messages.
2. **`Runtime.halt(0)`** — forcefully terminate the JVM. Instant but prevents any cleanup: SSE streams are severed mid-flight, the browser never receives a quit event, and no goodbye message is shown.
3. **`Promise[Nothing, Unit]`** — a one-shot signal. Any component can complete it to request shutdown. The main fiber awaits the promise, then tears down components in order.

## Decision

A `Promise[Nothing, Unit]` acts as the shutdown signal shared between all components.

- **TUI quit:** `shutdown.succeed(())` — the TUI loop exits, the main fiber continues.
- **Web quit:** `shutdown.succeed(())` via the `/api/quit` endpoint — the response is sent, then shutdown proceeds.
- **SSE quit event:** The `/api/events` stream merges `shutdown.await` as a `quit` event type, so the browser shows the goodbye screen before the server goes down.
- **Main orchestration:** After `shutdown.await` resolves, the main fiber prints "Goodbye!" to the terminal, waits briefly for the SSE quit event to reach the browser, then interrupts the server fiber.

```scala
shutdown <- Promise.make[Nothing, Unit]
// ... fork server, run TUI loop ...
_ <- shutdown.await
_ <- printLine("Goodbye!")
_ <- ZIO.sleep(500.millis)  // let SSE quit event reach browser
_ <- serverFiber.interrupt
```

## Consequences

**Benefits:**
- `Promise` is the idiomatic ZIO primitive for a one-shot signal — equivalent to `Deferred` in Cats Effect or `CompletableFuture` in Java.
- No noisy `InterruptedException` logs. Shutdown is cooperative, not abrupt.
- Both UIs get a goodbye message: the TUI via `printLine`, the browser via an SSE `quit` event.
- `Promise.succeed` is idempotent — multiple quit signals (e.g. TUI and web quit simultaneously) are harmless.

**Trade-offs:**
- The 500ms sleep before server interruption is a heuristic. If the browser's SSE connection is slow, it might miss the quit event. Acceptable for a local development tool.
- All components must receive the `shutdown` promise as a parameter, slightly widening their signatures.

# Timed games — plan

> Status: **Phases A–E done** (2026-06-25) — timed games are complete:
> server-authoritative clock core (A), timed vs-bot budgeting (B), client/lobby/
> PvP clock-config threading (C), the web-ui (D) — the new-game clock control
> + the live clock display — and the optional tournament-mirror clock passthrough
> (E). Decisions locked. See **"Tie-ins"** for the overlap with the in-progress
> replay & analysis features.
>
> Adds optional timed/clocked
> games to piChess's own games (Local / Host-PvP / Vs-Bot) with the
> **game-service as the authoritative clock**, surfaces clocks in the GUI, and
> feeds our bot its remaining time in timed vs-bot games. The bot is already
> time-aware (`TimeManager` + `Search.bestMoveWithBudget`); this closes the gap
> on the human/GUI side. Tournament games are already clocked by NowChess
> (authoritative there; the bot handles it) — out of scope except an optional
> spectate-mirror clock passthrough.

## What exists vs what's missing
- **Bot side is time-aware** ✅ — `TimeManager.budgetMs(remainingMs, incMs)`
  (`bot-engine/.../TimeManager.scala:50`) + `Search.bestMoveWithBudget(state,
  budgetMillis, history, fallbackDepth)` (`Search.scala:60`) drive Lichess/
  tournament play and flag-protect each move.
- **piChess's own games are clockless** ❌ — `NewGameRequest`
  (`proto/.../game_service.proto`), domain `GameState`, game-service
  `SessionState`, `BoardStateDto` (`api`), the SSE pipeline, and the web-ui carry
  **no time at all**. `GameStatus` is `Playing/Checkmate/Draw/Resignation` — no
  `Timeout` (the enum comment already notes Timeout is the planned next case).

## Core design — server-authoritative clock (the key requirement)
The game-service is the single source of truth. Per timed game it holds
**`ClockState(whiteMs, blackMs, incrementMs, runningSince: Option[Instant])`** in
the **session layer** (`SessionState`/`GameSnapshot` — *not* the pure-rules
`GameState`; clocks are a service concern, exactly like `Resignation`/forfeit are
service-imposed terminal states, not rules outcomes).

- **Decrement on move** — in the `SubscriptionRef[SessionState]` path that
  `subscribeGame` streams (`GameController.makeMove`): on a completed move
  `elapsed = now − runningSince`; `moverMs −= elapsed`; `moverMs += incrementMs`
  (Fischer); `runningSince := now` for the opponent. The clock starts running
  only after move 1 (lichess convention).
- **Flagging is server-decided** via a per-game **timeout daemon** fiber (~250 ms
  ticker — the same shape as the NowChess server's 1 s timeout daemon): when the
  side-to-move's `remaining − (now − runningSince) ≤ 0`, the service ends the game
  with **`GameStatus.Timeout(winner = opponent)`** and pushes. Mirrors `forfeit`
  exactly (`GameController.forfeit`).
- **The client never flags.** The browser interpolates the active clock down
  locally for smooth display, but every server push overwrites it and only a
  server `Timeout` push ends the game. The client measures elapsed since
  *receipt* (its own monotonic clock), so no client/server skew handshake is
  needed; drift only accrues within one move and resets on the next push.
- **Persistence / reconnect** — `ClockState` rides with the persisted session;
  `subscribeGame` re-emits current state on reconnect and the client resumes
  interpolating. `runningSince` makes a mid-think reload correct.

## Data-model changes
1. **domain** `GameStatus` → add `case Timeout(winner: Color)` (sticky-terminal,
   set by the service like `Resignation`). Ripples to `GameStatusDto` (api), the
   draw/result humanizers, and the game-end banner.
2. **game-service** new `ClockState` embedded in `SessionState`/`GameSnapshot`.
3. **proto** `NewGameRequest` → `int32 initial_seconds`, `int32 increment_seconds`
   (`0/0` = untimed, preserving today's behavior). `StateReply` unchanged — the
   clock rides inside `board_state`.
4. **api** `BoardStateDto` → add `clock: Option[ClockDto]`, where
   `ClockDto(whiteMs: Long, blackMs: Long, runningFor: Option[String])`. Reuses
   the existing boopickle round-trip and the single `state` SSE event — **no new
   codec, no new SSE event, no gateway change** (`WebController.serveEvents` just
   forwards the decoded `BoardStateDto`).
5. **api** `VsBotSettings` + `CreateGameRequest` + the lobby create DTO
   (`NewLobbyInput`/`Lobby`) → carry `initialSeconds`/`incrementSeconds`.

## Clock lifecycle
- **newGame** (`GameServiceLive.newGame` / `VsBotOrchestrator.newGameVsBot`):
  initialise `ClockState` from the request (`whiteMs = blackMs = initial_seconds
  × 1000`, `incrementMs`, `runningSince = None`). `0` initial ⇒ untimed (no
  `ClockState`, no daemon — the current path).
- **makeMove** (`GameController.makeMove`): decrement + increment as above; start
  the per-game timeout daemon on the first move; push state (carrying `ClockDto`).
- **timeout daemon**: per timed game, a forked fiber checks the running side
  every ~250 ms; on flag → `endWith(Timeout(winner))`, publish `GameEnded`,
  cancel the daemon.
- **game end** (any reason): cancel the daemon, freeze the clock.

## Bot time-awareness (timed vs-bot games)
Small integration — the engine already supports it. In the bot-reply path
(`VsBotOrchestrator.playBotMove` / `GrpcServer.playBotMove`), when the game is
timed: `budget = TimeManager.budgetMs(botRemainingMs, incrementMs)` →
`search.bestMoveWithBudget(state, budget, history)` instead of the fixed-depth
`bestMove`. The server decrements the bot's clock by its real think time (same as
a human), so the bot is subject to its own clock; `TimeManager` keeps it
flag-safe. `BotConfig` gains the bot side's remaining-ms accessor (read from
`ClockState` at move time).

## PvP / lobby clock config
Thread `initialSeconds`/`incrementSeconds` from the host form → `NewLobbyInput` →
`Lobby` → `LobbyService.startGame` → the gateway `registerPlayers` call →
game-service game creation (alongside the existing `allowSpectate`/
`spectatorLimit`).

## Web-ui
### New-game clock control (confirmed design)
Mirrors the **Allow-spectators ↔ Spectator-limit** precedent
(`hostModeDetails:2630-2640`, which fades the dependent row with `.is-erased`
when its gate is off):
- **`Components.checkboxRow(enableClockVar, "Enable clock")`** — default off
  (untimed remains the default; off ⇒ unlimited time).
- A **clock-time row** wrapped in `div(className <-- enableClockVar.signal.map(on
  => if on then "" else "is-erased"))` holding `Components.rangeSlider(
  clockMinutesVar, min = 1, max = 10)` (step 1 min, **default 5 min**). Greyed +
  non-interactive (§5.9) when the clock is off. Label shows the live value
  (e.g. "Clock — 5 min").
- **Enable-clock ↔ Allow-undo are mutually exclusive** (a timed game can't take
  back, per decision 1): checking either greys the other with `.is-erased` and
  clears it. Defaults arranged so "Enable clock" is directly checkable (undo
  defaults off in these forms).
- **Increment:** the GUI exposes initial time only ⇒ GUI games are
  **sudden-death (increment 0)**. The Fischer `incrementSeconds` field stays in
  the model for tournament games + a possible future "+increment" control.
- Wired into `VsBotSettings` / the lobby create payload. Present on all three
  modes — **Local** (both clocks, one device), **Host PvP**, **Vs Bot**
  (decision 3).

### Clock display (game screen)
- A per-side clock face by the player labels / `statusIndicator`
  (`Main.scala:1160`), flip-aware (`flippedVar`), the active side ticking.
- A single `EventStream.periodic(250 ms)` (the idiom already used for the Grafana
  refresh bar) drives a `clockVar` interpolated from the last `state` push:
  `displayedActiveMs = pushedActiveMs − (now − receiptTime)`, clamped ≥ 0.
  `mm:ss`, tenths under 10 s. Low-time urgency uses the existing marker /
  `#hand-drawn` vocabulary (e.g. marker-red under 10 s) — no new primitives.
- **Game-end**: the §5.10 banner gains "TIMEOUT · WHITE WINS"; move input enters
  the cancelled state as for checkmate.
- **Spectators / Watch** get clocks for free — `spectatorBoardArea` mirrors the
  player layout and the mirror's `BoardStateDto` carries the clock.
- Pure bits (`formatClock`, interpolation math) go in `Logic.scala` with
  `LogicSpec` tests.

## Tournament games (Phase E — done)
NowChess is authoritative and the bot already handles it. **Done:**
`TournamentSpectate`'s follower carries the upstream `clock.whiteTime/blackTime`
into the mirror's `ClockState` so the spectate board shows the real tournament
clocks. Because NowChess owns the clock, the mirror's clock is **display-only**:
a new `SetClock` rpc (`SetClockRequest{game_id, white_ms, black_ms, running}` →
`GameController.setClock`) overwrites the session clock verbatim — no decrement,
no flagging, no daemon, no persistence — and rides the existing SSE/`ClockDto`
into the Phase-D `clockBar` (already rendered in the spectator view). On each
1 s poll the follower replays new moves then re-pushes the clock (`running =
!terminal`, so it interpolates during play and freezes at game end). An untimed
tournament game (no upstream `clock` block) is a no-op ⇒ no clock shown.

## Tie-ins with the in-progress replay & analysis features
These are being built in parallel and are largely independent, but they meet
timed games at four points:

1. **`GameStatus.Timeout` is a new terminal (shared, coordinate now).** Phase A
   added `GameStatus.Timeout(winner)` → `GameStatusDto("timeout", winner)` →
   PGN `1-0`/`0-1`. Anything that renders a result — the game-end **banner**
   (§5.10), the result-card summary, the replay end-screen, analysis of the
   final position — must handle the **`"timeout"`** status kind (e.g.
   "TIMEOUT · WHITE WINS"). The DTO carries it today; the banner/replay code
   (in the replay/analysis batch) is the consumer. An exhaustive match on
   `GameStatusDto.kind` there needs the new case.
2. **Per-ply clocks in replay are derivable, not stored.** Replay steps through
   historical positions; a timed-game replay wants each ply's remaining time +
   time-used-per-move. Phase A stores only the *current* `ClockState`, not a
   per-ply history — by design. `MoveMade` events already carry `occurredAt`
   (epoch-ms), so **time-per-move = Δ of consecutive move timestamps**, and
   remaining-at-ply = initial − cumulative-used + increments. Replay/analysis
   should reconstruct clocks from move timestamps + the clock config rather than
   piChess storing a per-ply clock. ⇒ the one requirement on us: if replay loads
   a *finished/persisted* game (not the live session), the **clock config
   (initial + increment) must be persisted with the game** — Phase A keeps it in
   the in-memory session only, so persisted-replay needs the config stored
   (small addition when replay needs it).
3. **Analysis can surface time-per-move.** Same move-timestamp data lets the
   post-game analysis correlate eval-drops with time spent ("45s on the
   blunder"). No work needed from Phase A; it just provides the clock context.
4. **The web-ui clock face (Phase D) should be replay-reusable.** Build the
   clock display as a pure component driven by `(whiteMs, blackMs, runningFor)`
   so replay can feed it historical (paused) values — no live interpolation in
   replay mode.

**Shared-file coordination:** both efforts touch `BoardStateDto` (this adds
`clock`; analysis may add per-move eval) — additive but merge-prone — and both
render on/near the game screen (clock face vs replay controls / banner).

## Phasing
- ✅ **A — server clock core (done):** `GameStatus.Timeout`, `ClockState`, proto +
  `BoardStateDto.clock`/`ClockDto`, decrement-on-move (Fischer), the per-game
  timeout daemon (in coverage-excluded `GrpcServer`; its decision logic
  `ClockState.flagged` + `GameController.flagIfTimedOut` is unit-tested). Clock
  lives in the in-memory session (no cross-restart persistence yet — see tie-in
  #2). game-service 100% module gate.
- ✅ **B — timed vs-bot (done):** `GrpcMappers.botMoveBudgetMs` (pure, tested)
  derives a flag-safe budget from the bot's live clock via `TimeManager.budgetMs`;
  `GrpcServer.playBotMove` branches `bestMoveWithBudget` (timed) vs fixed-depth
  `bestMove` (untimed).
- ✅ **C — PvP/lobby clock config (done):** clock threaded onto
  `CreateGameRequest` → gateway `newGameRequestFor` → `NewGameRequest` for *any*
  mode (local/host/vs-bot); and `CreateLobbyRequest` → `NewLobbyInput` → domain
  `Lobby` → `createLobby` so a hosted lobby carries its time control. (The game's
  clock comes from `CreateGameRequest` at creation, not from `registerPlayers` —
  that only sets the spectator policy.) All clock fields default `0` = untimed.
- ✅ **D — web-ui (done):** `Logic.formatClock`/`clockRemainingMs`/`clockIsUrgent`
  (pure, unit-tested). New-game **clock control** (`clockControl`: "Enable clock"
  checkbox + a "N minutes per side" slider 1–10, default 5, off ⇒ unlimited),
  mutually exclusive with Allow-undo via the `.is-erased` precedent, on all three
  modes; wired into `CreateGameRequest` (local/vs-bot) + `CreateLobbyRequest`
  (host), forcing `allowUndo=false` when timed. **Clock display** (`clockBar`/
  `clockFace`): flip-aware, the running side interpolated locally from the last
  push every 250ms (server stays authoritative), low-time accent-red; rendered
  for player + spectator views; built as a reusable face replay can drive with
  paused values. **Timeout banner** ("X wins on time"). CSS `.clock-bar`/
  `.clock-face`/`.is-urgent`. web-ui compiles + links; 37 `Logic` tests pass
  (web-ui isn't scoverage-gated). *Known simplification:* both clock faces stack
  at the top of the board rather than top/bottom split — a later placement tweak.
- ✅ **E — tournament-mirror clock passthrough (done):** new `SetClock` rpc →
  `GameController.setClock` (display-only clock overwrite, covered) +
  coverage-excluded `GrpcServer.setClock`; `TracingGameServiceClient` forwards it
  (covered); `TournamentSpectate` decodes the upstream `clock{whiteTime,blackTime}`
  and pushes it onto the mirror each poll (coverage-excluded). game-service stays
  at 100%/100% (statement/branch) module coverage.

## Testing
- game-service: decrement/increment math, timeout-daemon flagging, untimed path
  unchanged, persistence/reconnect (100% gate).
- gateway: `BoardStateDto` clock round-trips through SSE.
- bot: budget computed from remaining clock; bot never flags.
- web-ui: `Logic` clock formatting + interpolation (zio-test; web-ui isn't
  scoverage-gated).

## Decisions (locked 2026-06-25)
1. **Undo in timed games — disabled.** Enable-clock ↔ Allow-undo are mutually
   exclusive in the new-game form (each greys/clears the other, §5.9), modelled
   on Allow-spectators ↔ Spectator-limit.
2. **Fischer increment** in the model. GUI exposes initial time only (sudden
   death, increment 0); increment field retained for tournaments/future.
3. **All three modes** get the clock option (Local / Host / Vs Bot).
4. **Control = "Enable clock" checkbox + a clock-time slider** (1–10 min, step
   1 min, default 5 min); off ⇒ unlimited. Handled like Allow-spectators.

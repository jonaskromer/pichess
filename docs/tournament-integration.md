# Tournament Integration — NowChess `tournament-server`

How to make the piChess bot participate in tournaments hosted by
[`maichess/tournament-server`](https://github.com/maichess/tournament-server)
(the "NowChess Tournament API", v2.0.0, modelled on the Lichess API style).

> **Status: implemented & live-tested.** The `bot-tournament` sbt module is built
> and unit-tested (100% statement + branch coverage), and has been run **end-to-end
> against a locally-running `tournament-server`**: two `TournamentBridge` clients
> registered, joined, played a full game and reached a server-validated **checkmate
> (94 plies)** — proving register → join → broadcast-`gameStart` → colour
> self-filter → `getGame` → stream → turn-loop → `makeMove` (accepted) → `gameEnd`.
> See "Server quirks found in live testing" below; the "Open operational
> questions" at the end remain.
>
> **Verified** against a local clone of the server (`../tournament-server`):
> `api/openapi.yaml`, `http/codec/JsonCodecs.scala`, `domain/event/*`,
> `domain/game/{Game,ChessRules}.scala`, `http/routes/{Auth,Game,Stream}Routes.scala`,
> `service/JwtAuthService.scala`. Wire formats below are the server's actual
> encoders, not just the spec.

## What the server is

A self-contained Scala tournament server (zio-http) that manages games, moves,
and outcomes internally. **Bots are pure clients** — the same architectural
shape as the Lichess Bot API that piChess already speaks (`bot-lichess`).
Servers: `https://nowchess.janis-eccarius.de` (prod),
`https://st.nowchess.janis-eccarius.de` (staging), `http://localhost:8086`
(local). Auth is a JWT bearer; identity is for attribution, not security.

A **director** (a non-bot identity) creates and starts tournaments; bots only
join and play:

```
director: POST /api/tournament            (create)        ─┐
bot:      POST /api/auth/register {isBot:true} → {id,token} │ before start
bot:      POST /api/tournament/{id}/join                    │ (status=created)
director: POST /api/tournament/{id}/start                  ─┘

bot:  GET  /api/tournament/{id}/stream                 (NDJSON, keep open)
      → on gameStart{round, gameId, color}:
        GET  /api/tournament/{id}/game/{gameId}/stream (NDJSON)
        POST /api/tournament/{id}/game/{gameId}/move/{uci}
      → repeat per round until tournamentFinished
```

## Verified wire formats (the bits that matter)

**Auth** — `POST /api/auth/register` body `{name, isBot?}` → `201 {id, token}`.
The JWT is HMAC-HS256 over `{sub, isBot, name}` with **no `exp`** (never
expires). `register` is **idempotent by `(name, isBot)`**: re-registering the
same name+isBot returns the *same* `id`. ⇒ no token persistence needed — on
restart we just re-register with the same name and get a working token for the
same bot identity. The director-added path works the same way (director
pre-registers our name via `POST /api/bots`; we mint a token for that name).

**Tournament stream** events (flat, `type`-discriminated, one JSON/line):

| `type`               | fields                          |
| -------------------- | ------------------------------- |
| `tournamentStarted`  | —                               |
| `roundStarted`       | `round`                         |
| `gameStart`          | `round`, `gameId`, `color`      |
| `roundFinished`      | `round`                         |
| `tournamentFinished` | `winner` (BotRef)               |

**Game stream** events (flat, `type`-discriminated). First line on connect is
always a synthesized `gameState` snapshot, then live events:

| `type`      | fields                                                              |
| ----------- | ------------------------------------------------------------------ |
| `gameState` | `fen`, `moves`, `turn`, `clock{whiteTime,blackTime}`, `status`, `winner` |
| `move`      | `uci`, `fen`, `turn`, `clock{whiteTime,blackTime}`                  |
| `gameEnd`   | `winner`, `status`                                                  |

- `color`/`turn`/`winner` are `"white"`/`"black"` (winner nullable).
- `status`: `pending` \| `ongoing` \| `checkmate` \| `stalemate` \| `draw` \| `resigned` \| `timeout`.
- **`clock.whiteTime`/`blackTime` are `Double` SECONDS** (Lichess uses ms).
- **Increment is NOT in game events** — only in the tournament's `Clock`
  (`GET /api/tournament/{id}` → `clock.increment`, integer seconds).
- **`gameStart` is BROADCAST, both colours, every game** — ⚠️ the single biggest
  gotcha, found by reading `GameActivation`: for each activated game the server
  publishes `gameStart{gameId, White}` **and** `gameStart{gameId, Black}` to the
  shared tournament stream, which every subscriber receives — including for
  games it isn't in. So `gameStart.color` does **not** identify our colour, and
  receiving a `gameStart` does **not** mean we're a player. The bot must
  **self-filter**: dedupe by `gameId`, then `GET /api/tournament/{id}/game/{gameId}`
  and match its **registered id** against `white`/`black` to learn its colour
  (or skip). The per-game stream carries no player ids either, so the `GET` is
  the only source. (`gameStart` fires only when a game becomes `ongoing`, so a
  pending game is announced when it activates, not before.)
- **Identity chain**: `register` → JWT `sub` = our bot id; `join` adds
  `BotRef(sub, name)` as a participant; games are built with those `BotRef`s;
  `move` is authorised by `game.currentPlayer.id == sub`. So matching our
  registered id against the game's players is exactly what the server checks.
- **No timeout enforcement** in this server version — the server never flags on
  time and clocks aren't decremented server-side. The bot can't force a flag; its
  `TimeManager` budget is still applied (bounded think time) but is advisory.

**Move** — `POST /.../move/{uci}` → `200 {"ok":true}` or `4xx {"error":...}`
(`409` = not your turn / illegal). Turn is enforced from the JWT `sub`; no body.

## Compatibility verdict

| Check                | Result                                                                                  |
| -------------------- | --------------------------------------------------------------------------------------- |
| **UCI move format**  | ✅ Match. Castling = king two-square (`e1g1`/`e1c1`), promotion `e7e8q`, EP diagonal. piChess's `chess.codec.UciCodec.serialize` emits exactly this; server `ChessRules.isPseudoLegalKingMove` expects exactly this. |
| **Position source**  | ✅ Easier than Lichess — every event carries the post-move `fen`, so no move replay; parse `fen` directly with `SyncCodec`. |
| **Standard chess**   | ✅ Variant is hardcoded `"standard"`. Custom/thematic start positions possible — engine plays from the given FEN (opening book just won't match; harmless). |
| **Engine / search**  | ✅ `bot-engine` reused 100 % (search, HCE+NNUE hybrid eval, TT). No changes. The Lichess Syzygy **tablebase oracle is deliberately NOT wired** here (`TournamentBotMain:26` — an external HTTP API we don't assume is reachable from a tournament host); the NNUE-backed search stands alone. |
| **Never-resign**     | ✅ No bot resign/abort endpoint exists; piChess already never resigns. |
| **Reconnect safety** | ✅ Plain NDJSON, no heartbeats. Per-game streams **retry on drop** (`retry(Schedule.fixed(5s))`); the server re-emits the `gameState` snapshot and we recompute from `fen`, so play resumes (the per-game `search`/TT is reused across reconnects). The tournament stream has no replay, but per-game fibers are `forkDaemon`ed so they survive a tournament-stream reconnect too — no duplicate forks, no dropped games. |
| **gameStart fan-out** | ⚠️ Handled. Broadcast both-colours-every-game ⇒ bot self-filters by registered id + dedupes by gameId (see note above). |

## Running it (CLI)

One command, via the `make` target (`make tournament-bot`). It auto-registers
piChess (idempotent) and plays. The runnable entrypoint is
`chess.bot.tournament.TournamentBotMain`; env knobs: `TOURNAMENT_BASE_URL`,
`TOURNAMENT_ID` (exact), `TOURNAMENT_NAME` (substring to **wait for** and
auto-join the moment it opens), `TOURNAMENT_BOT_NAME` (default `piChess`),
`TOURNAMENT_MOVE_DEPTH`, `TOURNAMENT_LAZYSMP`.

```bash
# Wait for the next "Tournament 0.x" on the HTWG server and auto-join as piChess:
make tournament-bot NAME="Tournament 0."

# Join a specific tournament by id:
make tournament-bot ID=767252df

# Override server / bot name / fallback depth:
make tournament-bot SERVER=http://141.37.123.132:8086 BOT=piChess NAME="Tournament 0." DEPTH=6
```

It's long-running (stays connected, plays every game). On a server, wrap it so
it survives the SSH session — e.g. `tmux new -s pichess 'make tournament-bot NAME="Tournament 0."'`
or `nohup make tournament-bot NAME="Tournament 0." &>/tmp/pichess-bot.log &`.
Resolution precedence: `TOURNAMENT_ID` > `TOURNAMENT_NAME` (poll every 10s until
a `created` tournament's name matches) > auto-pick the first open tournament.
(Needs the repo + `sbt` + a JDK on the host; for a no-sbt binary, `sbt
botTournament/stage` produces `bot-tournament/target/universal/stage/bin/`.)

## How it's built

The `bot-tournament` module is a second protocol adapter the size of
`bot-lichess` **minus the engine** — no engine, search, or eval changes.

### The `bot-tournament` module

A new sbt project `botTournament` (`name := "pichess-bot-tournament"`, package
`chess.bot.tournament`) mirrors `bot-lichess` in `build.sbt`:
`dependsOn(domain.jvm, rules, codec, botEngine)`, deps `sttp.client3 %% "zio"` +
`zio-json` (`build.sbt:263-275`).

- **`TournamentApiClient`** (trait + sttp impl) — `register(name)`,
  `listTournaments` (the `created`/joinable list, for auto-pick),
  `getTournament(id)` (to read `clock.increment`), `getGame(id, gameId)` (to read
  the players → our colour), `joinTournament(id)`, `streamTournament(id)`,
  `streamGame(id, gameId)`, `makeMove(id, gameId, uci)`. Bearer = the JWT from
  `register`, held in a `Ref` it populates; reuses the sttp NDJSON-stream and
  `postExpectOk` patterns from `BotApiClient`.
- **`TournamentEvent.scala`** — zio-json ADTs for the two streams. Events are
  **flat** with a `type` discriminator (`@jsonDiscriminator("type")` +
  `@jsonHint`), unlike Lichess's nested `gameFull{state}`. A `"white"`/`"black"`
  `Color` decoder; nullable `winner` → `Option`.
- **`TournamentRunner`** — pure decision logic, simpler than `GameRunner`:
  takes `ourColor` (resolved by the bridge — see below), parses event `fen` via
  `SyncCodec`, and emits `MoveFrom` when `status == ongoing && turn == ourColor`.
  Mapping: `ongoing` → play, `pending` → wait, terminal statuses / `gameEnd` →
  stop. `move` events have no `status` → drive off `turn`; rely on `gameEnd`.
- **`TournamentBridge`** — `register` → (`join` while `created`, tolerating
  `409`/director-added) → fetch increment → `streamTournament`. On `gameStart`,
  `resolveOurColor` **ignores the broadcast `color`**: dedupe by `gameId`, `GET`
  the game, match our registered id against `white`/`black` → our colour (or skip
  if not ours / duplicate). Then fork a per-game fiber (fresh-isolated
  `searchFactory`), drive `TournamentRunner`, POST moves. Never resign.
- **`TournamentBotMain`** — entrypoint (`ZIOAppDefault`). Env:
  `TOURNAMENT_BASE_URL`, `TOURNAMENT_ID` (or auto-pick the first `created`
  tournament via `listTournaments`), `TOURNAMENT_BOT_NAME` (default `pichess`),
  `TOURNAMENT_WEIGHTS_VERSION` (default `8`), `TOURNAMENT_MOVE_DEPTH` (fallback
  depth, default `6`), `TOURNAMENT_LAZYSMP` (default on). Reuses the
  weights/budget/LazySMP wiring from `LichessBotMain`; **no tablebase oracle** is
  wired (`TournamentBotMain:26`).

### Clock handling (the one real gotcha)

Feed `TimeManager.budgetMs` in **ms**: convert `clock.whiteTime/blackTime`
(seconds, `Double`) ×1000, and thread the tournament `clock.increment` (seconds,
fetched once via `getTournament`) ×1000, since per-move events omit it.

### Tests & running

Four ZIO Test specs mirror `bot-lichess`'s style, all against a stubbed
`TournamentApiClient` (no live server needed): `TournamentRunnerSpec` (decision
table — snapshot/move/end, pending-wait, colour/turn), `TournamentBridgeSpec`
(self-filter + dedupe, per-game orchestration, stream-drop reconnect,
never-resign), `TournamentApiClientSpec` (URL composition, runtime bearer,
NDJSON decode, error surfacing) and `TournamentEventCodecSpec` (literal-decode
contract against the exact server JSON). `TournamentBotMain` is coverage-excluded
like the other `*Main` entrypoints (`build.sbt:271`).

Run it like the Lichess bot — straight from sbt:
`TOURNAMENT_ID=… sbt 'botTournament/run'`. It is also containerised:
`make tournament-bot` builds the `pichess-bot-tournament` image, run via the
compose `tournament` profile (`docker compose --profile tournament up`) or the
k8s `full` overlay.

### Metrics & Grafana

The bot emits Prometheus metrics **in-process** on `:9107/metrics` (the shared
`chess.obs.MetricsHttpServer`, forked beside the control API in
`TournamentBotMain`), scraped by Prometheus's `tournament` job and rendered by
the auto-provisioned **`piChess — tournament`** Grafana dashboard. All the rich
data is already in-process, so nothing is routed through Kafka — and the bot can
play an off-cluster tournament and still be scraped.

The metric families (all labels bounded — `opponent`, `color`, `result`,
`status`, `family`, `move`):

| Metric | What it answers |
| --- | --- |
| `pichess_tournament_games_finished_total{opponent,color,result,status}` | **win/loss/draw vs each opponent** + termination reason (checkmate/resigned/timeout/…) — one counter drives win-rate, the W/L/D split, and the "how games end" panel |
| `pichess_tournament_games_started_total{opponent,color}` | games played vs each opponent, colour balance |
| `pichess_tournament_openings_total{opponent,family}` | ECO opening **family per opponent** (UCI-prefix classifier, `Openings.family`) |
| `pichess_tournament_first_move_total{move}` | opening-move distribution |
| `pichess_tournament_think_seconds{color}` / `…_move_budget_seconds` | actual think-time and allocated budget per move (histograms) |
| `pichess_tournament_game_length_plies` | game length (histogram) |
| `pichess_tournament_clock_remaining_seconds{color}` | live clock pressure (gauge) |
| `pichess_tournament_active_games` / `…_active_tournaments` | liveness gauges |
| `pichess_tournament_moves_total{color}` | move throughput |
| `…_stream_reconnects_total` / `…_move_failures_total` / `…_search_no_move_total` | operational health |

The pure classification feeding these — `GameOutcome.classify` (result × status)
and `Openings` (append / first-move / plies / ECO family) — is unit-tested
(`GameOutcomeSpec`, `OpeningsSpec`); `TournamentMetrics` is emission-only glue
(coverage-excluded, like `TournamentBotMain`). Locally: `docker compose
--profile tournament --profile obs up` (set `TOURNAMENT_ID`/`TOURNAMENT_NAME`),
then open Grafana at `:3000`.

### Reusable as-is

`bot-engine` (all of it: search, HCE+NNUE hybrid eval, TT), `chess.codec`
(`UciCodec` + the `FenParserRegex` FEN parser), `TimeManager` (with the s→ms
conversion), the `searchFactory` / LazySMP wiring, the never-resign policy. The
one new helper is the module's own `internal.SyncCodec` — a thinner sync
FEN-parse adapter than the Lichess one (NowChess always sends a real FEN, so no
`"startpos"` special-case).

## Server quirks found in live testing

Two server-side issues surfaced while running a real game. **Neither affects our
bot's correctness** — they're the server's, and noted here for the integration:

- **Concurrent `join` race.** `TournamentService.join` is a non-atomic
  get→modify→save over the in-memory repo, so two bots that `join` at the exact
  same instant can clobber each other and one is silently dropped (observed:
  `nbPlayers` stuck at 1). We deploy a single bot joining once, so this won't bite
  us — but if joins are scripted in parallel, stagger them.
- **`Pairing` JSON shape ≠ OpenAPI.** The spec shows a top-level
  `pairing.gameId`, but the real encoder nests it under `pairing.matches[].gameId`
  (no top-level `gameId`). Irrelevant to our bot (it gets `gameId` from
  `gameStart`), but anything reading `GET /api/tournament/{id}/round/{n}` must
  read `matches[].gameId`.

## Server compliance log

**2026-06-24 — server update** (`feat: add CORS, clock increments, timeouts, SSE
streaming improvements`; package renamed `nowchess.tournament` → `tournament`).
Re-verified our client against the new code:

- ❌→✅ **Heartbeats (the one real break).** The streams now interleave a
  `{"type":"heartbeat"}` NDJSON line every ~10s (`NdjsonStream`). Our discriminated
  decoders had no such case, so every heartbeat failed the stream → reconnect
  churn. **Fixed:** added an ignorable `Heartbeat` case to both event ADTs
  (`@jsonHint("heartbeat")`); the runner returns `None` and the bridge dispatch
  silently ignores it. Tests + 100 % coverage retained.
- ⚠️ **Timeouts are now ENFORCED.** `ClockRules` + a 1s timeout daemon flag a bot
  that overruns its clock (`status=timeout`, opponent wins); clocks now decrement
  and credit the Fischer increment. Previously unenforced. No client code change
  (our `TimeManager` already budgets with a safety buffer), **but timing is now
  load-bearing** → see the performance agenda in `docs/tournament-ui-plan.md`.
- ✅ **Tolerated extra fields.** Clock objects gained `increment`; the game JSON
  gained `startedAt`/`endedAt`. zio-json ignores them; pinned by a regression test.
- ✅ **Unchanged & still compliant:** event discriminators/shapes, auth (register
  idempotent, no exp), `move` → `{"ok":true}`, `getGame` players, list, and UCI
  rules (castling king-two-square, promotion, EP). Streams are still NDJSON
  (`application/x-ndjson`), not SSE/WS. CORS + the package rename don't affect us.

## Open operational questions (not blockers)

- **Time control of the actual tournament** — confirm the director's
  `clockLimit`/`clockIncrement` so `TimeManager` budgeting is tuned (e.g. blitz
  300+3 vs bullet). `bot-engine` is fine either way.
- **Self-join vs director-added** — decide whether we join ourselves (need the
  tournament id + open registration) or get added by the director (we just need
  to register our name and stream). Both are supported by the same client.
- **No threefold/▢ adjudication server-side** — server `isDraw` only covers
  stalemate, 50-move, and basic insufficient material (no threefold). Games may
  run long; our engine handles it, just worth knowing for time budgeting.

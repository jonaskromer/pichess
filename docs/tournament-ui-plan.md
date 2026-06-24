# Tournament UI + Containerized Bot — Plan

A web-UI **Tournaments** section (replacing the "Watch a bot game" link) that
lists NowChess tournaments, lets you **enter piChess** (Join) and **Spectate**
games, backed by a **containerized, multi-tournament bot** that the gateway
signals. Builds on `docs/tournament-integration.md` (the verified protocol) and
the `bot-tournament` module.

> Status: **phases 1–5 done — feature complete.** Containerized multi-tournament
> bot + control API + gateway proxy + spectate mirrors + the unified
> `GET /spectate/games` aggregator (native + tournament + Lichess) + the web-ui
> (Spectate + Tournaments screens, built to `design.md`). Decisions honoured:
> containerized bot signalled by the gateway, multi-tournament, **Grafana-style**
> refresh (auto-poll default OFF + manual), and the web-ui composed from the
> `design.md` helper catalogue. See the phase list below for per-phase detail.

## Constraints from the server (already verified)

- **No SSE, no WebSockets** — streams are NDJSON over long-lived GET; the
  tournament **list is not streamed** (`GET /api/tournament` is a plain request).
- ⇒ the browser must not hit `141.37.123.132:8086` directly (CORS + the bot's
  JWT must stay server-side). **The gateway proxies everything.**
- Spectating reuses the existing `LichessSpectate` pattern: relay upstream NDJSON
  → a **mirror game** in game-service → browser watches via the existing
  `/api/games/{id}/events` **SSE** feed and the existing read-only board.

## Architecture

```
browser ──(same-origin)──> gateway ──┬─ GET  /api/tournament            (NowChess server)  ← list relay
                                      ├─ register / join signal          (NowChess + bot ctrl)
                                      ├─ NDJSON game stream → mirror      (NowChess → game-service)
                                      └─ /api/games/{id}/events (SSE)     (existing board pipeline)

gateway ──(k8s svc)──> pichess-bot-tournament  (containerized; control API; plays N tournaments)
                              └─ register piChess once → joinAndPlay(id) per tournament (engine here)
```

The **engine runs only in the bot container** — never in the gateway. The
gateway is a thin proxy + the spectate mirror (no `bot-engine` dependency).

## Components

### 1. Containerized multi-tournament bot (`bot-tournament`)

- **Multi-tournament refactor of `TournamentBridge`:** split `run` into
  `register` once (shared `TournamentApiClient` + token) and
  `joinAndPlay(tournamentId)` (join → read clock → `streamTournament` → per-game
  fibers). A manager holds `Ref[Map[tournamentId, Fiber]]` to dedupe and track
  active tournaments. The per-game logic (`resolveOurColor`, `runGame`, retry,
  colour self-filter) is unchanged. The shared `EngineBundle` + global
  `ParallelismBudget` (LazySMP) are reused across all tournaments.
- **Control API** (small zio-http server in the bot container; new dep):
  - `POST /control/tournaments {id}` → register-if-needed + `joinAndPlay(id)` (idempotent).
  - `GET  /control/tournaments` → active tournaments + per-game status.
  - `DELETE /control/tournaments/{id}` → stop following one.
  - Still supports env seeding (`TOURNAMENT_ID` / `TOURNAMENT_NAME` wait-and-join)
    on startup, now feeding the same manager.
- **Packaging/deploy:** add `JavaAppPackaging` + `DockerPlugin` to `botTournament`
  (build.sbt), publish via `release.yml` (gates on 100% coverage — already met),
  a `deploy/k8s/base` Deployment + Service (`pichess-bot-tournament`) + kustomize
  entry, env via ConfigMap (`TOURNAMENT_BASE_URL`, `TOURNAMENT_BOT_NAME`). Likely
  its own optional tier/overlay so the core stack can deploy without it.
- **Resource note (4-core/12 GB VM):** N concurrent tournaments ⇒ N concurrent
  games ⇒ N main search threads contending for cores. The server enforces **no
  timeout**, so the bot won't flag — only think shallower under load. Cap
  concurrency (e.g. a max-active-tournaments env) and/or lower `TOURNAMENT_MOVE_DEPTH`.

### 2. Gateway `TournamentProxy` controller

Mirrors `LichessSpectate` / `LobbyProxy`; holds `TOURNAMENT_BASE_URL` and the
bot-control URL; no engine.

- `GET  /tournament/list` → relay `GET /api/tournament` (optionally a ~2–3 s
  TTL cache so many browsers = one upstream call).
- `POST /tournament/register` → ensure piChess registered (auth/register +
  `/api/bots`); idempotent, token cached, **safeties** below. Auto-called on
  landing-page open.
- `POST /tournament/{id}/join` → call the bot's `POST /control/tournaments {id}`
  (the bot does the actual register/join/play). Returns once accepted.
- `GET  /tournament/{id}/game/{gameId}/spectate` → relay the upstream game
  NDJSON into a game-service **mirror**, return the mirror id; browser then uses
  the existing `/api/games/{id}/events` SSE + read-only board.

**Auto-register safeties:** gateway-side only; idempotent (re-register returns
the same id within a server lifetime); token cached so it never spams; gated on
`TOURNAMENT_BASE_URL` configured; no-op if already registered this process;
failures are surfaced as a toast, not fatal.

### 3. web-ui Tournaments landing page

- Replace the **"Watch a bot game"** link (`web-ui/.../Main.scala:2090`) with
  **"Tournaments"** → a new view (new route + render fn in `Main.scala`,
  testable bits in `Logic.scala`).
- On open: auto-register (gateway) + **one** list fetch.
- List grouped `created` / `started` / `finished`; each row shows name, players,
  format, status, with:
  - **Enter piChess** button (created only) → `POST /tournament/{id}/join`.
  - **Spectate** button (started; for each live game) → open spectate mirror →
    navigate to the existing read-only board.
- **Grafana-style refresh bar** (the requested UX):
  - **⟳ Refresh** button — manual fetch, always available.
  - **Auto: Off ▾** dropdown — `Off` (default) / `5s` / `10s` / `30s` / `1m`.
  - "updated Ns ago" indicator.
  - Laminar wiring: `intervalVar: Var[Option[Int]]` (None = off, **default
    None**), `lastFetchVar: Var[Long]`. Driver:
    `intervalVar.signal.flatMap { case Some(n) => EventStream.periodic(n*1000); case None => EventStream.empty } --> refresh`,
    plus the manual button → `refresh`. The timer only exists while auto is on;
    cleared on view teardown. **Nothing polls by default** — important given the
    upstream has 90+ tournaments and no push.

## Spectate — unified ongoing-games list (all four types)

A **Spectate** view (replacing the home **"Watch a bot game"** link) lists *all
ongoing games*, filterable by type — **PvP / PvBot / Bot-v-Lichess / Tournament**.
It is **separate from the Join/Tournament list** (that lists *tournaments* to
enter piChess into; this lists *games* to watch) but **shares UI components**.

### Shared components (web-ui, Laminar)
- `FilterBar` — type chips (All / PvP / PvBot / Bot-v-Lichess / Tournament) +
  the **Grafana-style refresh** bar (⟳ manual + `Auto: Off ▾` default-off).
- `GamesList` / `GameRow` — a row = two players + a status badge + one action.
- **Spectate view** = `GamesList` over *ongoing games*, action **Spectate**.
- **Join view** = the same `FilterBar`/`GameRow` over *tournaments*, action
  **Enter piChess** / **Withdraw**. Distinct entities, shared rendering.

### Built to `docs/design.md` (per-user directive — the design system wins)
Every element composes from the existing helper catalogue
(`web-ui/.../components/`) + bespoke classes; **no new bespoke button shapes,
colours, tilts, or px**. Concrete mapping:
- **Screen** — `screenLayout("spectate", …)` skeleton (§6): one title card
  (`titleCard` + the single `.screen-heading`, logo font) counter-tilted against a
  content card (`contentCard`). `.back-link` in the title card.
- **FilterBar type chips** — the **tab strip** (§5.3): `.tab-strip` of `.btn-link`
  children; the active type pins its marker stripe (`is-active`); a type with zero
  games takes the §5.9 disabled treatment (erased text + muted marker), never
  removed.
- **Refresh bar** — **⟳** is a `Components.iconButton` (single-glyph control,
  matching the existing `joinScreen` refresh — §5.1 lists "Refresh" under
  secondary, but the bare glyph reads as a `.btn-icon` and consistency with the
  existing refresh UX wins). **`Auto: Off ▾`** is a `selectInput` (handwritten
  "Auto" label per §5.2). "updated Ns ago" is `--font-press` system-data text.
- **Games list** — a **`.scrap-table`** via the new **`Components.scrapTable`**
  helper (§5.8 — the ruled-ledger scrap, Phase 5 is its first consumer): `<th>`
  = newsprint cuttings (`.newsprint-shadow > .code-inline`); player **names** in
  `--font-hand`; **status** badge; spectator **count/limit** numeric,
  `--font-press`, right-aligned; hand-drawn header underline + column separators
  on pseudo-element rules (`filter: url(#hand-drawn)`, never text cells), no row
  rules, no outer frame.
- **Row action** — **Spectate** / **Enter piChess** as `Components.linkButton`
  (`.btn-link`), matching the existing `publicLobbyAction` list-row precedent — a
  per-row cyan post-it would flood the secondary colour (§2.4). A **full** game
  (`spectateable=false`) renders the link in the canonical **`.is-erased`** state
  (§5.9 — loose handwriting is erased, not struck), still listed. A
  non-enterable tournament simply has no action (N/A, like `publicLobbyAction`).
- **Empty / loading / error** — empty per-type = a quiet handwritten line (not a
  card); fetch error = `showToast(...)` (§5.6), never fatal.
- **Theme** — read tokens only; the table, chips, and badges inherit
  `currentColor` so dark mode flows through the one `<html>.dark` flip (§8).

### Data sources — and the new backend each needs
The list is one gateway endpoint, **`GET /spectate/games`**, that fans out to the
sources (per-source failure tolerated → that type just shows empty), returning a
uniform DTO `OngoingGame(id, type, white, black, status, spectators, limit,
spectateable, …ids needed to spectate)`:

| Type | Source | New work |
| --- | --- | --- |
| **Tournament** | NowChess: `GET /api/tournament` (started) → `GET /round/{cur}` pairings with `winner==null` | none (gateway aggregates) |
| **PvP** / **PvBot** | game-service | **new `ListActiveGames` gRPC** over the in-process `GameSessions` (returns gameId + `vsBot` + status + turn); gateway relays + labels players. Tier-independent (sessions are in-process regardless of the persistence backend). |
| **Bot-v-Lichess** | Lichess `GET /api/account/playing` (gateway has `LICHESS_BOT_TOKEN`) | gateway query → our bot's live games. "Watch a *fresh* bot game" stays a separate explicit create action. |

### Spectate mechanics differ by type (key insight)
- **PvP / PvBot** are *already our game-service games* → **no mirror**; the
  Spectate action just opens the existing read-only board on `/api/games/{id}/events`.
- **Tournament / Bot-v-Lichess** are external → **mirror** into game-service
  (the `TournamentSpectate` / `LichessSpectate` follower pattern), then the same
  board/SSE. So the *list* is unified; the Spectate *action* routes by type
  (native → direct board; external → create mirror → board).

### Spectator policy & count (gateway `SpectatorPresence`)
The gateway already tracks, per game, a `SpectatorPolicy(allowSpectate, limit)`
(`setPolicy`, from the lobby hand-off) and a **live count** (`SubscriptionRef[Int]`
from open SSE connections); games with **no policy** (vs-bot, local, external
mirrors) are unrestricted. The aggregator enriches each game from this and
applies the requested rules:
- **`allowSpectate == false` → omit the game entirely** (a host opted out).
- **otherwise list it**, with `spectators` = current count and `limit`.
- **full** (`limit > 0 && spectators >= limit`) → listed but `spectateable=false`
  (the **Spectate** button is disabled / shows "Full").
- no policy ⇒ unrestricted ⇒ always `spectateable`, count shown, no cap.

Needs one read-only addition to `SpectatorPresence` (it currently only exposes
`changes`/`admit`/`setPolicy`): a `snapshot(gameId): (SpectatorPolicy?, count)`
(or a bulk `infos`) for the aggregator to join against. For **external**
(tournament / Lichess) games the count is the **shared mirror's** count — so the
gateway must **dedupe mirrors per external game** (one mirror per
`(tournamentId,gameId)` / Lichess id), which also avoids N mirrors for N viewers;
before anyone watches, `spectators=0`, `spectateable=true` (public, no cap).

### Main-menu entry (start screen)
A dedicated, prominent **Tournament** button in the start-screen button group
(`startScreen`, `web-ui/.../Main.scala`), which today is `New Game / Join /
Settings`. Recommended order **`New Game / Join / Tournament / Settings`** (above
Settings — Settings stays the trailing utility; the crown + underline already
make Tournament pop). New `Screen.Tournaments` route.
- ⚠️ **Naming**: the existing **"Join"** button is the *lobby* multiplayer join
  (`Screen.Join`), unrelated to tournaments — don't reuse it. The new button is
  **Tournament** → the tournament view (the Join-piChess + Spectate lists).
- **Crown icon**: `objects/crown.svg` exists; add
  `.icon-crown { --icon-url: url(/web/doodle_icons/objects/crown.svg); }` to
  `bespoke.css` (then `icon("crown")`, rendered as a `currentColor` mask like the
  others). Place it beside the label (extend `linkButton` to accept an optional
  leading icon, or a small `featuredButton` variant).
- **Hand-drawn multi-underline**: a small `web/scribble-underline.svg` — one
  continuous, uneven multi-stroke path (the "quickly underlined 3× without
  lifting the pen" look), used as a `background-image` (no-repeat, bottom,
  sized in `em`) on a `.scribble-underline` class under the label. Matches the
  existing doodle/`marker-stripe.svg` aesthetic; relative units per house style.

### Coverage
`ListActiveGames` (game-service is in the 100% root gate) + the gateway
aggregator (incl. the policy filter / count-join / full-but-listed rules) are
unit-tested (mock the sources + `SpectatorPresence`; assert per-source
tolerance). The mirror *followers* stay coverage-excluded (external glue).
Adds an `OngoingGame` DTO to the `api` module (+ a proto change for
`ListActiveGames`, regenerated).

## Phasing

1. ✅ **DONE — Multi-tournament bot refactor.** `TournamentBridge.run` split into
   register-free `TournamentBridge.playTournament` (one tournament) + a new
   `TournamentManager` (registers piChess once via `Ref.Synchronized`; `join`
   forks a supervised per-tournament fiber, deduped by id; `leave`/`activeTournaments`
   for the Phase-2 control API; per-tournament reconnect-retry + cleanup).
   `TournamentBotMain` now resolves one tournament, `manager.join`s it, and stays
   alive (`ZIO.never`) so further joins can be added. 70 tests, 100% coverage.
2. ✅ **DONE — Bot control API + containerisation.** `TournamentControlApi` (zio-http)
   exposes `GET /health`, `GET /control/tournaments`, `POST/DELETE /control/tournaments/{id}`
   over the manager; `TournamentBotMain` serves it (port `TOURNAMENT_CONTROL_PORT`,
   default 8080) and seeds an initial join from `TOURNAMENT_ID`/`TOURNAMENT_NAME`
   in the background. `botTournament` is now `JavaAppPackaging`+`DockerPlugin`
   (image `pichess-bot-tournament`, temurin:23-jre, EXPOSE 8080), wired into
   `release.yml` and a `deploy/k8s/overlays/full/bot-tournament.yaml`
   Deployment+Service (**ClusterIP-only — never via ingress**), with the image
   pinned in the full kustomization. 76 tests, 100% coverage; kustomize + Docker
   stage validated. The gateway (Phase 3) signals it at `http://bot-tournament:8080`.
3. **Gateway `TournamentProxy`** —
   - ✅ **DONE (proxy half).** `TournamentProxy` (gateway controller, same-origin,
     no engine): `GET /tournament/list` relays the public NowChess list;
     `POST`/`DELETE /tournament/{id}/join` signal the bot's control API
     (`PICHESS_TOURNAMENT_URL` / `PICHESS_BOT_CONTROL_URL`, env-configurable).
     Wired into `WebController.routes` + `GatewayMain`. 131 gateway tests, 100%
     coverage (mirrors `LobbyProxy`). No bot registration in the gateway — the
     bot self-registers on join, so the original "register endpoint" is moot.
   - ✅ **DONE (spectate half).** `TournamentSpectate` (coverage-excluded glue,
     like `LichessSpectate`): `POST /tournament/{id}/game/{gameId}/spectate` →
     **deduped** mirror game in game-service (one per `(tid,gid)`, via a
     `Ref.Synchronized`), forks a follower that polls the **public** game
     snapshot (no token) and replays new moves → browser watches via the
     existing `/api/games/{id}/events` SSE + read-only board. Wired into
     `WebController.routes` + `GatewayMain`; added to the gateway
     `coverageExcludedFiles`. Gateway 131 tests, 100% coverage. ⏳ Still needs a
     **live smoke test** (game-service + gateway + tournament server + a live
     game) — the real validation for excluded glue, as done for the bot.
4. **Spectate aggregator (backend)** — the unified ongoing-games list (see
   "Spectate" above):
   - ✅ **DONE (4a — game-service `ListActiveGames`).** New proto rpc
     `ListActiveGames(ListActiveGamesRequest) → ActiveGamesReply { repeated
     ActiveGame{game_id, vs_bot, bot_side} }`; `GameSessions.all` snapshots live
     sessions; `GrpcServer.listActiveGames` returns the still-playing games
     tagged PvP/PvBot via `BotConfigRepository`; `TracingGameServiceClient`
     forwards it. game-service 100 tests / gateway 132 tests, 100% coverage
     (GrpcServer is coverage-excluded glue; `GameSessions.all` + the decorator
     are tested).
   - ✅ **DONE (4b-i — gateway aggregator, native source + rules).** `OngoingGame`
     DTO in `api` (cross-compiled, codecs + round-trip test); `SpectatorPresence.info`
     (read-only policy + live count, side-effect-free); `SpectateIndex` controller
     `GET /spectate/games` → game-service `ListActiveGames` joined with presence,
     projected by the pure `toOngoingNative` applying the spectator rules (omit
     `allowSpectate=false`; full → listed-but-not-spectateable; PvP/PvBot labels).
     Wired into `WebController.routes` (no new params — reuses `client`+`presence`).
     gateway 142 tests / api round-trip, 100% coverage; 502 fallback covered.
   - ✅ **DONE (4b-ii — tournament source).** `SpectateIndex.tournamentGames`
     fans NowChess into `GET /spectate/games`: started tournaments
     (`/api/tournament`) → current-round pairings (`matches[].gameId`) → per-game
     snapshot, keep `status==ongoing`, label from the snapshot names,
     `tournamentId` set (public/uncapped; shared-mirror count is a later
     refinement). Native + tournament sources fetched independently, each
     `catchAll`-tolerated so one outage never blanks the list. `routes` now takes
     `tournamentBaseUrl`; wired through `WebController`. Minimal private decoders
     for the public NowChess JSON. gateway 144 tests, 100% coverage (incl. the
     decode-error and dead-source-tolerance paths via a fake upstream).
   - ✅ **DONE (4c — Lichess source).** `SpectateIndex.lichessGames(base, token)`
     reads the bot account's `GET /api/account/playing` (Bearer token) →
     `toOngoingLichess` rows (`gameType="lichess"`, bot side from `color`,
     opponent from username→id→generic; public/uncapped → spectateable, count 0).
     `None` token ⇒ no rows, no I/O ⇒ Lichess simply absent. `getJson` generalised
     to take request `Headers` (auth). The three sources are now fanned via one
     `ZIO.foreach(sources)(_.catchAll(tolerate))` (single tolerated recovery).
     `LichessSpectate` gained `POST /lichess/games/{id}/spectate` — mirror an
     **existing** Lichess game (no challenge) by following its public stream
     (coverage-excluded like the rest of that bridge). `routes` takes
     `lichessToken`; wired through `WebController`. gateway 149 tests, 100%
     coverage (pure label/colour/fallback branches + a fake-Lichess fan-out).
   - ✅ **DONE (4d — default-scoped tournament source + per-source timeout).**
     `GET /spectate/games?scope=ours` (the default) shows only piChess's own
     tournament games: `SpectateIndex.tournamentGamesScoped` first asks our bot
     service which tournaments it is in (`GET
     {PICHESS_BOT_CONTROL_URL}/control/tournaments`, cheap + cluster-local) and,
     when it is in none, returns with **no call to the external NowChess
     server** — so the common idle/local-dev case can no longer hang. When it
     is in some, it fans only those and keeps games whose side is our bot
     (`PICHESS_BOT_NAME`, default `pichess`; must match the bot's
     `TOURNAMENT_BOT_NAME` — the `full` overlay sets both to `piChess`).
     `scope=all` opts into the full external fan (`tournamentGames`). The web-ui
     exposes this as a default-on "piChess bot games only" checkbox on the
     Spectate filter bar. Sources are now fanned via
     `ZIO.foreachPar(_)(tolerate)`, where `tolerate` also caps each source at
     **2 s** (belt-and-braces against a configured-but-unreachable server).
     Added scoped-filter, empty-active short-circuit, and `scope=all` route
     tests.
   - ✅ **DONE (5 — web-ui, built to `design.md`).** Two new routed screens in
     `Main.scala` sharing one vocabulary. **Spectate** (`#spectate`, replaces the
     home "Watch a bot game" link): a `FilterBar` (the `Components.tabStrip` —
     All / PvP / PvBot / Bot v Lichess / Tournament chips with live counts; an
     empty type chip is disabled) over a **`.scrap-table`** of `GET /spectate/games`
     rows (White · Black · status badge · "Watching" count · Spectate action).
     Row actions are `Components.linkButton` (matching the `publicLobbyAction`
     precedent — no per-row post-its, §2.4). Native rows open the existing
     `/api/games/{id}/events` board directly; tournament/lichess rows POST their
     mirror endpoint then open the mirror; a **full** game is listed but its link
     is **`.is-erased`** (§5.9), not removed. A "Challenge a bot" link keeps the
     old fresh-Lichess-game flow.
     **Tournaments** (`#tournaments`, its own main-menu entry above Settings —
     crown doodle `.icon-crown` + a `.scribble-underline` multi-stroke mark) lists
     `GET /tournament/list` (created→started→finished) with **Enter piChess** on
     joinable rows. Both carry the **Grafana refresh bar**: ⟳ manual + an
     `Auto: Off ▾` select (default Off — nothing polls) + an "updated Ns ago"
     readout; auto-poll is `intervalVar.signal.flatMapSwitch(periodic | empty)`.
     New CSS: `.scrap-table` (ledger rules via `filter:url(#hand-drawn)` pseudos;
     header cuttings; rem hairlines), `.icon-crown`, `.scribble-underline` (+
     `web/scribble-underline.svg`), `.status-done`; the disabled state reuses the
     canonical `.is-erased`/`.is-struck`. A design.md compliance sweep applied
     these: row actions → `.btn-link`, headers → cuttings via `Components.scrapTable`,
     full → `.is-erased`, handwritten "Auto" label, px→rem. Pure bits (`SpectateFilter`,
     `matchesFilter`/`filterGames`/`spectateFilterChips`, `humanizeAge`,
     `refreshIntervals`, `gameBadge`/`tournamentBadge`/`canEnterTournament`,
     `TournamentRow`/`TournamentList`/`orderTournaments`) live in `Logic.scala`,
     unit-tested (web-ui 33 tests; module is `coverageEnabled := false` — Scala.js
     isn't scoverage-instrumentable, so Logic is tested but not gate-counted).
     Compiles + links (`webUi/fastLinkJS`); gateway resource-gen copies `main.js`.
5. Tests/coverage throughout (gateway controller + web-ui `Logic` unit-tested as
   the existing ones are; bot manager unit-tested with a stub client).

## Performance & cost (agenda)

**Why it's now load-bearing:** the updated server **enforces timeouts** (a flagged
bot loses on time — previously it couldn't). So low, predictable per-move cost
and correct time-management are correctness requirements, not polish — especially
on the shared 4-core/12 GB VM with N concurrent tournaments.

Dimensions to test (a `bench-tournament` + load harness, reusing the existing
JMH / k6 / Gatling stack — `make bench-bot`, `project-k6-requirement`):

1. **Flag-safety under contention** — every move must POST within
   `remaining − SafetyBufferMs`. Test at nominal load AND with injected CPU
   contention (background load + several concurrent games), since the VM is
   shared. (Per `feedback-sequential-evals`: make contention the *controlled*
   variable, don't let it leak in.) This is the headline metric.
2. **Move-decision latency vs. budget** — `Search.bestMoveWithBudget` never
   overruns the budget; measure the overrun distribution (JMH + a tournament
   harness).
3. **Connection / stream overhead** — NDJSON decode throughput, heartbeat
   handling (~free), per-game reconnect cost, memory per open stream.
4. **Multi-tournament footprint** — CPU/RAM per concurrent tournament/game; fix a
   safe **max-concurrency cap** default for the VM (shared `EngineBundle`, per-game
   TT sizing).
5. **Gateway proxy cost** — list-relay latency (+ optional short-TTL cache so N
   browsers = 1 upstream call) and spectate-mirror overhead (one mirror per game
   across viewers).
6. **Idle cost** — a connected, waiting bot (heartbeats only) should be ~0 CPU.

Cheap-connection levers to evaluate: read `increment` straight from the game-clock
event (the updated server now includes it) to **drop the per-tournament
`getTournament` call**; coalesce spectate mirrors; cap concurrency; tune
`TOURNAMENT_MOVE_DEPTH` under load.

## Test coverage (agenda)

Hold **100 % statement + branch** on all tournament code (already met on
`bot-tournament`). Each new piece ships with tests keeping the gate:
- multi-tournament manager — unit-tested with a stub `TournamentApiClient`;
- bot control API — route tests;
- gateway `TournamentProxy` — controller tests (mirror the existing gateway
  controller specs);
- web-ui — pure bits in `Logic.scala` unit-tested (as the existing web-ui tests).
Entry points (`*Main`) + Docker/k8s glue stay coverage-excluded per convention;
end-to-end behaviour validated by the local harness pattern (see the integration
doc's live-test section). Perf harnesses live under the existing `bench`/`k6`
modules, not counted in unit coverage.

## Open questions / risks

- **Bot-control auth:** the control API must only be reachable by the gateway
  (k8s ClusterIP, not exposed via ingress) — it can enter piChess into arbitrary
  tournaments, so don't make it public.
- **Spectate scale:** each spectator opens a mirror + upstream stream; reuse one
  mirror per game across viewers (the gateway already tracks spectators in
  `SpectatorPresence`).
- **Max concurrency** on the VM — pick a sane default cap.
- **Server is in-memory:** registrations + tournaments vanish on a server
  restart; the bot re-registers on demand, so the UI must tolerate the upstream
  resetting (a list fetch will just show the new state).

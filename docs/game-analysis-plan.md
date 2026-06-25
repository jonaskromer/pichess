# Game analysis — plan

> Status: **Phases 1–2 implemented** (2026-06-25). Phase 1 — PGN `%clk`/`%emt` +
> NAG + ECO/Opening codec (`PgnMove`/`MoveAnnotation`/`Nag`, `PgnCodec` clock/emt/
> nag encode+decode, `PgnSerializer.serializeAnnotated`, annotation-preserving
> `PgnParser`; also fixed the latent `1...`/glued-glyph import bugs). Phase 2 —
> ECO dataset + opening-namer (`chess.opening`: `EcoBook`/`Opening`/`Families`, a
> committable 111-line `eco.tsv`, longest-SAN-prefix → "B90 · …Najdorf" with
> coarse-family fallback; dataset validated legal+canonical against the rules
> engine). codec 100% stmt+branch gate (321 tests); opening-book + PGN-ingest
> consumers verified. **Phase 3a** (game archive core) — `GameArchive` model,
> `GameArchiveRepository` + in-memory impl, and the pure idempotent archive
> logic (`ArchiveProjection`/`ArchiveBuilder`/`GameArchiver` + Kafka consumer
> glue): event→ply→PGN-with-clocks+opening, order-agnostic + replay-safe; codec/
> persistence-api/repository 100% gates. **Phase 4a** (analysis engine) — new
> `analysis` module: `WinProb` (cp→win%, Lichess model), `MoveQuality`
> (win-%-drop → full NAG `MoveClass` + per-side accuracy), and `GameAnalyzer`
> (engine-driven per-move rating + opening + accuracy over a parsed game); 100%
> gate, tested with a cheap evaluator. Phase 3b (DB backends + wiring +
> tournament recorder) and Phases 4b/5/6 remain.
>
> **Note (2026-06-25):** timed-games and replay are both **complete**, so the
> coordination seams are resolved — the GUI phases reuse replay's
> `positionAtPly` + clock face directly, and per-move clocks are now real (the
> archive can carry explicit `%clk`, analysis can correlate time-per-move).
> Decisions locked (see end). Adds post-game
> analysis to piChess: per-move quality glyphs (full NAG vocabulary), an eval /
> win-% graph, the **named opening** (ECO-level, within reason), per-move
> think-time, and a step-through review — for piChess's own games **and**
> tournament (bot-vs-bot) games. Built as three layers that are independently
> developable and decoupled — "in large parts" — from the in-flight **replay**
> and **timed-games** features (see [timed-games-plan.md](timed-games-plan.md)
> "Tie-ins"). Analysis runs **on-demand, cached**.

## Standards we adopt (the "is there a standard?" answer)
Yes — everything maps to **PGN** plus its de-facto annotation conventions, so a
single committable file carries the whole analyzed game and is portable to
Lichess/chess.com/SCID:

- **Moves**: standard PGN movetext (we already have `PgnSerializer`).
- **Clocks**: per-move `[%clk H:MM:SS.s]` comment (remaining clock after the
  move — the Lichess/chess.com standard) and/or `[%emt SS.s]` (elapsed move
  time). Time control in the `[TimeControl "180+2"]` header.
- **Move quality**: **NAGs** in the movetext — `$1 !`, `$2 ?`, `$3 !!`, `$4 ??`,
  `$5 !?`, `$6 ?!` (plus position-eval NAGs `$10`…`$19` if we want them). "Book"
  is not a NAG — we mark it separately.
- **Opening**: `[ECO "B90"]` + `[Opening "Sicilian Defense: Najdorf"]` headers.

So the persisted artifact is just an enriched PGN; the structured DTO is its
in-memory twin.

## What exists vs what's missing
**Have:**
- Engine scoring primitives — `Search.bestMoves(state, depth, k): List[(Move, cp)]`,
  `Search.evaluate`, `Search.principalVariation` (`bot-engine/Search.scala`). cp
  is white-relative; mate sentinel `Search.MateScore = 100_000`.
- `MoveMade(gameId, resultingFen, moveCoord, san, occurredAt)` events on
  `chess.game-events`; `occurredAt` (epoch-ms) ⇒ wall-time per move is derivable.
- PGN codec (`PgnSerializer`/`PgnParser`) + SAN (`notation/SanSerializer`).
- Coarse opening families (16) in `analytics/Eco.scala` (SAN) and
  `bot-tournament/Openings.scala` (UCI); 79 **named** lines in
  `bot-engine/.../openings/main-lines.pgn`; an unused `eco` column in bot-data.
- Web-ui: Laminar, `stateVar: Var[Option[BoardStateDto]]`, `renderMoveLog`,
  `board()` (always renders *latest*).

**Missing:**
- Games aren't persisted analyzably (only latest FEN). **Tournament games aren't
  persisted at all** (live only in the bot; it *does* see per-move `GameClock`).
- No analysis endpoint, no cp→win-%→NAG model, no PGN NAG/`%clk` output (parser
  *strips* `%clk`/NAGs today).
- Opening naming is coarse (Sicilian swallows Najdorf/Dragon/…); no ECO dataset.
- Move list has no glyphs/selection; board has no "view ply N"; no eval bar/graph.

---

## Architecture — three independent layers
Thin DTO contracts between them; each is separately testable.

### Layer 1 — Analyzable game archive (async, idempotent consumer)
**Goal:** every finished game (local + tournament) persisted with full moves and
the data to reconstruct clocks, exportable as PGN-with-`%clk`.

- **New `archive` consumer** (a new service or a slice of `repository`) that
  subscribes to `chess.game-events` — **non-blocking**, like the opening /
  analytics consumers. game-service is unchanged (no end-of-game write path,
  zero added latency on the hot path).
- **Idempotent, order-agnostic upsert per move.** Key = **`(gameId, ply)`**,
  where `ply` is **derived from `resultingFen`** (`(fullmove-1)*2 + sideBit`) so
  it's self-describing — safe under at-least-once redelivery, out-of-order
  arrival, and topic replay, and unique even under threefold repetition (move
  numbers differ). (We store `boardHash` alongside for integrity; we key on
  `ply` rather than `boardHash` precisely because repetitions reuse a hash.)
  Each upsert writes `{gameId, ply, san, uci, fenAfter, occurredAt, clockMs?}`.
- **`GameEnded` finalizes** the archive: assemble ordered plies → a `GameArchive`
  record + the PGN-with-`%clk` blob + headers (`Result`, `TimeControl`, players).
  Finalize is itself idempotent (rebuild from the per-move rows).
- **Clocks** (reconciling the timed-games plan's tie-in #2/#3 with the
  async-archiver steer):
  - **time-per-move** comes from consecutive `occurredAt` Δ (always available).
  - **remaining-per-ply** = `initial − cumulativeUsed + increments` from the
    **clock config**, which must travel with the game — captured by the archiver
    from `GameStarted` (coordinate: `GameStarted`/game-meta should carry the
    clock config; timed-games plan already names "persist clock config" as its
    one requirement here).
  - If timed-games later puts an explicit clock on `MoveMade`, the archiver
    prefers it (forward-compatible); otherwise it derives. Either way the archive
    ends up with per-move time — `clockMs` is `Optional`/authoritative-when-present.
- **Tournament games** (off the Kafka path, bot sometimes off-cluster): the bot
  gets a small **per-game recorder** that accumulates `(uci, fen, GameClock)` from
  `MovePlayed`/`StateSnapshot` and, at game end, **POSTs a ready PGN-with-`%clk`
  to the archive endpoint** (it already has everything, incl. authoritative
  clocks). This finally persists tournament games.
- **Store:** reuse the polyglot persistence pattern — a `GameArchiveRepository`
  (Mongo doc / Postgres row), one record per `gameId`. Compact (PGN + small
  per-ply table). Analysis results (Layer 2) cache back into the same record.

### Layer 2 — Engine analysis API
**Goal:** score every move + name the opening, server-side, reusing the loaded
engine.

- **Scoring:** for each ply, `bestMoves(pre, depth, k)` gives the engine's best
  line + cp; the played move's cp is its entry in that list (or `-evaluate(post)`).
  `cpLoss = bestCp − playedCp` (mate-aware, clamped). Convert cp→**win-%** with
  the standard logistic `win% = 50 + 50·(2/(1+exp(−0.00368208·cp)) − 1)`, and
  classify by **win-% drop** (robust across eval magnitudes), not raw cp.
- **Full NAG mapping** (see table below) → `best / !! / ! / !? / ?! / ? / ?? /
  book`. `!!/!/!?` need a light sacrifice/forced-move heuristic (material Δ from
  the evaluator + "only good move") — best-effort, "within reason".
- **Per-side accuracy** from average win-% loss (chess.com-style %).
- **Opening-namer:** bundle a committable **ECO TSV** (`eco | name | SAN prefix`,
  ~few hundred KB, from public ECO data e.g. lichess-org/chess-openings — aligns
  with "local + committable"). Longest-SAN-prefix match → `B90 · Sicilian,
  Najdorf`; falls back to the coarse 16-family table, then "Other". Also reports
  the ply the game **left book**.
- **Surface:** gRPC `AnalyzeGame(gameId | pgn, depth) → GameAnalysis{ opening,
  perMove:[{ply, evalCp, winPct, cpLoss, nag, isBook, bestMove, pv}],
  accuracyWhite, accuracyBlack }`; gateway exposes `POST /api/games/{id}/analyze`
  and `POST /api/analyze` (paste any PGN, incl. external). **On-demand + cache**:
  first request computes (progress streamed/polled), result cached in the archive
  → re-opens instantly.
- **Cost on the 4-core/12GB VM:** one game at a time (semaphore), modest fixed
  depth (configurable), reusing the already-resident engine; cache makes repeat
  views free. (`bestMoves` already exists — no new search code, just orchestration.)
- **PGN round-trip:** extend `PgnSerializer` to emit NAGs + `%clk`/`%emt` +
  ECO/Opening headers; stop `PgnParser` stripping `%clk`/NAGs (needed for import
  + round-trip). Small, standalone, unblocks Layer 1 export and external import.

### Layer 3 — Web-ui
**Goal:** render the analysis over the move list + board.

- **DTO:** new cross-compiled `GameAnalysisDto` (additive; does not perturb
  `BoardStateDto` beyond an optional handle). Fetched for a finished game via the
  Analyze action.
- **Components:**
  - **Move glyphs** — `renderMoveLog` emits a per-move NAG badge (`!!`/`?`/`??`…
    + a book marker) from the analysis map; styled in `bespoke.css` (marker
    red/green/blue, scrap/newsprint aesthetic).
  - **Eval bar + win-% graph** — vertical bar beside the board + a win-% area
    sparkline near `statusIndicator()`; click a point → jump to that ply.
  - **Opening label** — `B90 · Sicilian, Najdorf` as a newsprint clip by the move
    list header.
  - **Move-detail panel** — clicking a move shows engine best move + PV + eval
    (reuse the post-it/annotation-panel pattern).
  - **Step-through** — click a move → board shows that ply (the one piece shared
    with replay; see below).
- **Styling:** new `.move-glyph*`, `.eval-bar`, `.eval-graph`, `.opening-label`,
  `.analysis-panel` rules in `gateway/src/main/tailwind/bespoke.css`.

---

## Move-quality model (full NAG vocabulary)
Engine win-% (white-POV) → per-move drop = `bestWin% − playedWin%`. Indicative
thresholds (tunable; "good/best" carry no glyph):

| NAG | Glyph | Meaning | Trigger (best-effort) |
| --- | --- | --- | --- |
| —   | (book) | Book move | move matches the opening/ECO line (no eval judged) |
| —   | best  | Engine's top move | played == bestMove (no glyph) |
| `$1` | `!`  | Good move | only-good / clearly-correct non-obvious move (small loss, narrow alternatives) |
| `$3` | `!!` | Brilliant | best move **and** a sound sacrifice (material down, still winning) and non-obvious |
| `$5` | `!?` | Interesting | sound but not-best sharp/sac choice (tiny loss, sharpens) |
| `$6` | `?!` | Dubious / inaccuracy | win-% drop ~5–10% |
| `$2` | `?`  | Mistake | win-% drop ~10–20% |
| `$4` | `??` | Blunder | win-% drop ≳20% (or hangs decisive material) |

`!!/!/!?` are heuristic (need the sacrifice/forced detection) — shipped after the
robust `?!/?/??/best/book` core. Per-side **accuracy %** from mean win-% loss.

## Independence & coordination
The three features (analysis, **replay**, **timed-games**) are largely parallel;
they meet at small, additive seams — coordinate, don't serialize:

- **Replay ↔ analysis share exactly one primitive:** `positionAtPly(moveLog, n)`
  + a `viewingPlyVar`. ~80% of analysis (glyphs, eval bar, win-% graph, opening
  label, accuracy) annotates the **static** move list and needs no stepping. Only
  "click move → board jumps" overlaps replay. Land `positionAtPly` as a thin PR
  either feature can merge first; the second reuses it.
- **Timed-games ↔ analysis:** clocks are `Optional` throughout — analysis works
  without them and gains a time-per-move overlay once present. The archiver
  (Layer 1) is where the clock **config** capture (timed-games tie-in #2) and the
  time-per-move correlation (tie-in #3) actually land.
- **Shared `GameStatus.Timeout`:** any result-renderer (move list end, analysis
  of the final position) must handle the `"timeout"` `GameStatusDto.kind`
  (already in the DTO; see timed-games tie-in #1).
- **Merge-prone files:** `BoardStateDto`/new DTOs (additive fields), `renderMoveLog`
  + `board()` (glyphs / ply-view). Keep changes additive; coordinate the
  `positionAtPly` helper.

## Phasing
1. ✅ **PGN-with-`%clk`/NAGs + ECO headers** codec (serializer writes, parser
   preserves) — done; standalone; unblocks archive export + external import.
2. ✅ **ECO dataset + opening-namer** (pure, unit-tested) — done (`chess.opening`).
3. **Archive consumer + `GameArchiveRepository`** — depends on 1.
   - ✅ **3a (done):** `GameArchive`/`ArchivePly` (domain), `GameArchiveRepository`
     + `InMemoryGameArchiveRepository` (persistence/api, 100%), the pure
     idempotent logic `ArchiveProjection` (event→ply / result token) +
     `ArchiveBuilder` (plies→PGN-with-clocks+opening) + `GameArchiver`
     (dispatch/finalize/truncate) + the `KafkaGameArchiveConsumer` glue
     (repository, 100% on the covered parts). `PgnSerializer.serializeWithResult`
     added. All unit-tested; idempotent + order-agnostic + replay-safe.
   - ✅ **3b (done):** archive persisted across **all four backends** — Mongo /
     Postgres / Redis / Cassandra `GameArchiveRepository` (one JSON-blob entity
     each, shared `GameArchiveJson` codec) + `PersistenceLayers.archiveRepository`
     wiring + a `GameArchiveRepositoryContract` validated against **real DBs via
     testcontainers** (16 tests, full archive round-trip incl. nested plies) +
     `RepositoryMain` forks the archive consumer (own group). Revised 3a to
     accumulate plies **in-memory** (rebuilt by Kafka replay, cleared at finalize
     → bounded memory) so each backend stays a single simple entity.
   - ✅ **3b-tournament (done):** tournament games (off the Kafka path) now
     archive too. Repository ingest: `ArchiveSubmissionDto`/`SubmittedMoveDto`
     (repository-api), `POST /archives` + `GET /archives/{id}` routes, and a
     `TournamentArchive` builder that **replays the submitted UCI** through the
     rules engine to derive SAN + FEN, then builds the archive (opening +
     PGN-with-clocks). Bot side: a `GameRecorder` accumulates per-move UCI +
     clocks in the bridge and POSTs an `ArchiveSubmissionDto` on game end
     (`PICHESS_ARCHIVE_URL`, off by default; threaded optionally so play is
     untouched when unset). repository/repository-api/bot-tournament 100% gates.
4. **Engine analysis** — depends on 2.
   - ✅ **4a (done):** `analysis` module — `WinProb` (cp→win%), `MoveQuality`
     (win-%-drop → full NAG `MoveClass`, accuracy), `GameAnalyzer` (rate every
     move vs the engine's best + opening + per-side accuracy). Pure parts + the
     engine-driven analyzer, 100% gate. Brilliant(!!)/Interesting(!?) are
     represented but not yet emitted (need a sacrifice heuristic — follow-up).
   - ✅ **4b (done):** exposed end-to-end. `AnalysisService`/`CachedAnalysisService`
     (analysis module, PGN+depth → DTO, memoized) + cross-compiled
     `GameAnalysisDto`/`MoveAnalysisDto`/`OpeningDto`/`AnalyzeRequestDto` (api).
     gRPC `AnalyzeGame(pgn, depth) → AnalyzeReply{analysis_json}` on game-service
     (reuses the resident vs-bot engine; depth clamped; ECO book loaded once),
     proxied by the gateway as `POST /api/analyze` (tapir). On-demand + cached.
     Archive integration (analyze by gameId) lands with 3b.
5. ✅ **Web-ui: analysis UI (done)** — move-quality glyphs in the log, a
   win-% eval bar by the board, opening label + per-side accuracy, and a
   move-detail panel (class/eval/best/accuracy). Auto-fetched on game-end (`POST
   /api/analyze` with the PGN), cleared on return-to-play. Pure helpers in
   `Logic` (eval/glyph/accuracy/opening + ply lookups), unit-tested.
6. ✅ **Web-ui: step-through + move-detail (done)** — reuses replay's
   `activePlyVar`/`boardViewSignal` directly (no shared-primitive PR needed); the
   eval bar + detail panel are keyed on the same `activePlyVar`, so analysis
   scrubs in lock-step with the replay board. A cross-system `LogicSpec` test
   pins the shared 0-based ply indexing. (PV-as-SAN in the detail panel is a
   later polish — currently best-move + eval + class + accuracy.)

## Testing
- codec: PGN `%clk`/`%emt`/NAG/ECO round-trip (zio-test, 100% gate).
- opening-namer: prefix matching, variant granularity, fallbacks (pure, gated).
- analysis: cp→win-% + win-%-drop→NAG mapping (pure, gated); endpoint vs a known
  annotated game (golden test).
- archive consumer: idempotency / out-of-order / replay → same archive; finalize
  rebuild (gated).
- web-ui: `Logic` glyph/eval/opening formatting + `positionAtPly` (zio-test;
  web-ui isn't scoverage-gated).

## Open questions / risks
- **Analysis depth vs latency** on the 4-core VM — pick a default depth (likely
  ~10–12) that keeps a full game under a few seconds; cache hides repeat cost.
- **`!!/!/!?` precision** — sacrifice/forced heuristics false-positive; ship core
  first, tune brilliant/great behind the same endpoint.
- **ECO dataset choice/licensing** — prefer a public-domain/CC ECO TSV; commit a
  trimmed copy.
- **Archiver as new service vs repository slice** — lean to a focused new
  consumer to keep `repository` single-purpose; revisit if it's too thin.

## Decisions (locked 2026-06-25)
1. **Rating: full NAG vocabulary** (`$1`–`$6` + book), win-%-based; robust core
   (`?!/?/??/best/book`) first, heuristic `!!/!/!?` after.
2. **Clocks: async, idempotent archiver consumer** (order-agnostic, keyed
   `(gameId, ply)`); time-per-move from `occurredAt`, clock **config** persisted
   with the game; explicit clocks used when present (tournament always; timed
   local if surfaced). game-service stays non-blocking.
3. **Trigger: on-demand + cached** in the archive.
4. **Opening: bundled committable ECO TSV** (ECO-code granularity), coarse
   families as fallback.
5. **Persisted as enriched PGN** (`%clk`/`%emt` + NAGs + ECO/Opening headers) +
   a structured `GameArchive` twin.

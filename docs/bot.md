# piChess Bot — Engine, Online Client & Strength Measurement

The "bot" is piChess's chess engine plus everything around it: the search +
evaluation library, the offline training pipeline, the online Lichess client,
and the harness used to measure playing strength. This document is the single
source of truth for how those pieces fit together and — importantly — **how the
bot's Elo is actually measured**, because that has historically been reported
inconsistently (see [§5](#5-measuring-strength-elo)).

---

## 1. Module map

| Module | Role | Key types |
|---|---|---|
| **`bot-engine`** | The engine, as a pure library. Search + evaluation + bootstrap. No I/O. | `Search` / `AlphaBetaSearch`, `Evaluator` family, `EngineBundle`, `TranspositionTable`, `OpeningBook` |
| **`bot-data`** | DuckDB training corpus + an **offline** opening-book adapter. Not on the live play path — see [§4.1](#41-storage--bot-data-duckdb). | `Db`/`Schema`, `BookRepo`, `TrainingRepo`, `WeightsRepo`, `IngestedFilesRepo`, `DuckDbOpeningBook` |
| **`bot-train`** | Offline training & evaluation (no game serving). | `TexelTuner`, `SelfPlay`, `NnueDataGen`, `CorpusTrainer`, `TournamentMain`, `StockfishSearch` |
| **`bot-lichess`** | Online Lichess Bot-API client. | `LichessBotMain`, `Bridge`, `GameRunner`, `BotApiClient` |
| **`bot-tournament`** | External tournament-server client (NowChess-compatible). Deep-documented in [`tournament-integration.md`](tournament-integration.md). | `TournamentBotMain`, `TournamentBridge`, `TournamentRunner`, `TournamentApiClient` |
| **`nnue-train`** | Python (PyTorch/MPS) NNUE trainer producing the `.bin` nets. | `train_nnue.py`, `train_incremental.py` |

`bot-engine` is the shared core: `game-service` links it for "play vs the
computer", `bot-lichess` links it to play on Lichess, and `bot-tournament` links
it to an external tournament server (NowChess; see
[`tournament-integration.md`](tournament-integration.md)). Everything else builds
on top.

---

## 2. The engine (`bot-engine`)

### 2.1 Search — `AlphaBetaSearch`

Negamax α-β with a transposition table. Two entry points on the `Search` trait:

- **`bestMove(state, depth, history)`** — fixed-depth search. Always returns a
  move for any legal position (the root seeds `best` with the first legal move),
  returning `None` *only* at checkmate/stalemate. This is the path the
  **tournament/Elo harness** uses.
- **`bestMoveWithBudget(state, budgetMillis, …)`** — iterative deepening to a
  wall-clock deadline. This is the path the **live Lichess bot** uses. It runs
  depth 1, 2, … until the projected next iteration would exceed the budget.

Search heuristics are individual flags on `Search.alphaBeta(...)`. Defaults
reflect the A/B history (deltas were measured at depth 4, parallelism 1 — the
only trustworthy tournament mode; see the doc comments on `Search.scala`):

| Flag | Default | Measured effect |
|---|---|---|
| Quiescence | **ON** | +179 Elo (escapes the horizon effect) |
| Null-move pruning | **ON** | +16 Elo, 15% faster |
| SEE move ordering | **ON** | neutral at d4, +17 at d6 |
| Singular extensions | **ON** | +21 at d6 (gated `depth ≥ 5`) |
| Check extension | **ON** | +61 at p=1 |
| Counter-move seed | **ON** | prefills CMH from the master-game corpus |
| Iterative deepening (fixed-depth) | OFF | −24 at d4 (re-test at d6+) |
| Aspiration windows | OFF | −28 at d4 |
| LMP / futility | OFF | −52 at d4 |
| Counter-move history | OFF | −21 at d4 |

> ⚠️ **Parallel tournaments are unreliable for Elo.** The same flags swing
> wildly at `parallelism > 1` due to shared-TT concurrency noise (check
> extension measured +61 at p=1 but −424 at p=8). Always measure at p=1.

### 2.2 Evaluation — `Evaluator` family + `EngineBundle.EvalSource`

| `EvalSource` | What it is |
|---|---|
| `Hce` | Hand-crafted tapered evaluator over tuned `weights/vN.json`. |
| `Nnue` | Single NNUE net `/nnue-v1.bin` (Stockfish-distilled). Falls back to HCE if absent. |
| `NnueEns` | Ensemble `/nnue-ens-v1-s{1..k}.bin` (averaged). |
| `Hybrid` | **Default & strongest.** HCE backbone + NNUE correction, phase-tapered NNUE weight `hybridAlpha` **0.4 (opening) → 0.6 (endgame)** — re-tuned up from 0.3→0.5 at the depth-6 production search. |

The HCE weights are versioned. **`weights/v8.json` is the champion** ("+14 Elo
over v4"); v1 is the original Phase-5b snapshot.

> ⚠️ **Gotcha:** `EngineBundle.fromResources` defaults to `weightsVersion = 1`,
> **not** the champion v8. Callers that want maximum strength must pass
> `weightsVersion = 8` (the live Lichess bot does — `LICHESS_WEIGHTS_VERSION`
> defaults to 8).

### 2.3 Bootstrap — `EngineBundle`

`EngineBundle.fromResources(weightsVersion, evalSource, hybridAlpha, …)` loads
weights + opening book + NNUE from the classpath and assembles a ready `Search`.
The strongest production config is:

```scala
EngineBundle.fromResources(weightsVersion = 8) // Hybrid default, hybridAlpha = 0.4 → hybridAlphaEndgame = 0.6 (phase-tapered)
```

Other pieces: `TranspositionTable.inMemory` (capped, shared across a session),
`OpeningBook` (from `openings/main-lines.pgn`), and optional `TbAugmentedSearch`
(Syzygy tablebase oracle for ≤N-piece endgames).

### 2.4 Vs-computer config — `BotConfig` + `Difficulty`

`game-service` links `bot-engine` to "play vs the computer". A vs-bot game is
driven by a `BotConfig` — both it and `Difficulty` live in `bot-engine`, so the
gateway and game-service compose them from their own DTO shapes without a
proto↔Scala translation layer:

```scala
final case class BotConfig(botSide: Color, difficulty: Difficulty, allowUndo: Boolean)
```

- **`botSide`** — the colour the bot plays (the player gets the other side). If
  the bot is White it plays the opening move immediately.
- **`difficulty`** — a coarse 5-level enum, each pinned to a **fixed search
  depth**. That depth is the only knob that actually changes play today:

  | `Difficulty` | `searchDepth` | `noise` |
  |---|---|---|
  | `Beginner` | 1 | 0.3 |
  | `Easy`     | 2 | 0.1 |
  | `Medium` *(default)* | 3 | 0.0 |
  | `Hard`     | 4 | 0.0 |
  | `Expert`   | 5 | 0.0 |

  game-service maps difficulty → depth at the call site
  (`search.bestMove(state, cfg.difficulty.searchDepth, …)` in `GrpcServer` /
  `VsBotOrchestrator`); the search never sees `Difficulty` itself. The per-level
  **`noise`** field is **defined but unused** — a placeholder for a future
  "pick the Nth-best move with probability `noise`" blunder model. Phase-1 leans
  on depth alone for the skill spread.
- **`allowUndo`** — a **non-enforced UI hint**, not a rule. The server permits
  undo/redo regardless of this flag (the bot side can take back too); the client
  only uses it to grey out the take-back control in a "no take-backs" game (see
  the `allow_undo` field doc in `game_service.proto`).

**Per-game bot config is in-memory only.** game-service keeps it in a
`Ref[Map[GameId, BotConfig]]` (`BotConfigRepository.inMemory`) — never persisted.
So a vs-bot game created before a game-service restart **reverts to plain PvP**
afterwards: the config is lost and the server stops auto-replying for the bot
side. Promoting it to durable storage is a one-file `BotConfigRepository` swap.

---

## 3. The online Lichess bot (`bot-lichess`)

### 3.1 How it plays

`LichessBotMain` → loads the strongest `EngineBundle` (Hybrid, v8), builds a
**fresh, isolated `Search` per game** (own TT + heuristic tables; the loaded net
is shared), connects to the Lichess Bot API, and runs `Bridge.run` forever:

- **`BotApiClient`** — thin client over the Lichess Bot API (NDJSON event
  streams + accept/move/resign POSTs).
- **`GameRunner`** — pure decision logic: rebuilds the position from
  `initialFen + cumulative UCI moves`, decides whose turn it is, emits an
  `Action` (`MoveFrom` — carrying both clocks — / `None` / `GameOver` /
  `MalformedEvent`).
- **`Bridge`** — glues them: accepts **standard-variant, *rated*** challenges
  only (casual games burn the daily game cap but don't move the rating), forks a
  per-game fiber, runs the search on our turn, POSTs the move.
- **Adaptive per-move budget.** Each move is sized to the live clock by
  `TimeManager.budgetMs` (not a flat 2 s): it banks time from a conservative base
  and spends it where it matters — **game stage** (middlegame), **clock
  advantage** (when ahead), and **checks** (forcing) — clamped so it can never
  flag (50 ms–12 s). `LICHESS_SEARCH_DEPTH` is only a fallback floor; every move
  logs its chosen budget + both clocks for poll-free monitoring.

**Policy: the bot never resigns.** A competing bot makes the opponent prove the
win. If the search returns no move it logs and waits (the server ends genuinely
terminal games on its own). See [§6](#6-known-issues--fixed-bugs).

### 3.2 Running it

```bash
LICHESS_BOT_TOKEN=<token> sbt 'botLichess/run'
```

| Env var | Default | Meaning |
|---|---|---|
| `LICHESS_BOT_TOKEN` | *(required)* | Lichess bot-account API token |
| `LICHESS_BOT_USERNAME` | `pichess-htwg` | used to detect our colour |
| `LICHESS_WEIGHTS_VERSION` | `8` | HCE weights snapshot (champion = 8) |
| `LICHESS_SEARCH_DEPTH` | `6` | fallback fixed depth if the adaptive budget can't finish an iteration |
| `LICHESS_LAZYSMP` | `on` | use spare cores for LazySMP helper fibers (set `false` to disable) |
| `LICHESS_TABLEBASE` | `on` | Lichess 7-piece tablebase oracle (set `false` to disable) |

The per-move time budget is **not** an env var — it is computed adaptively from
the Lichess clock by `TimeManager` (§3.1). (The old fixed `LICHESS_MOVE_BUDGET_MS`
is no longer read.)

The account must be a **registered Lichess BOT account** (one-time, irreversible
`POST /api/bot/account/upgrade`, only possible with zero games played).

---

## 4. Training pipeline (`bot-train` + `nnue-train`)

- **`TexelTuner`** — tunes the HCE `weights/vN.json` against a labelled corpus.
- **`NnueDataGen`** — emits training rows (FEN + Stockfish eval target + PV).
- **`nnue-train/train_incremental.py`** — PyTorch/MPS trainer over Lichess
  Stockfish-eval shards; writes the `.bin` net in the Scala-readable layout.
- **`SelfPlay` / `SelfPlayTrainMain`** — self-play game generation.
- **`StockfishSearch`** — UCI wrapper used as a calibrated opponent (see §5).

### 4.1 Storage — `bot-data` (DuckDB)

The offline training / ingest data lives in a single **DuckDB** file behind a
thin JDBC facade (`Db` + `Schema`). The path is `PICHESS_DUCKDB_PATH` (default
`./chess-bot-training.duckdb`; tests pass `:memory:` for a fresh, isolated DB per
run). The schema is six `CREATE TABLE IF NOT EXISTS` tables (`Schema.scala`), so
opening a pre-existing file is a no-op:

| Table | Holds |
|---|---|
| `games`              | source-game metadata, one row per PGN game |
| `positions`          | every position seen, deduped by Zobrist |
| `position_moves`     | opening-book move stats (win/draw/loss + aggregate Elo) |
| `training_positions` | Texel-tuner input — sampled "quiet" positions + features + FEN |
| `eval_weights`       | versioned Texel-tuned weight snapshots |
| `ingested_files`     | per-file ingest markers → resumable corpus builds |

Four repos sit over those tables:

| Repo | Table | Role |
|---|---|---|
| `BookRepo`          | `position_moves`     | move-stat lookup + `bestMove` ranking; written by ingest / self-play |
| `TrainingRepo`      | `training_positions` | streams quiet rows to `TexelTuner`; appends batches |
| `WeightsRepo`       | `eval_weights`       | versioned read/write of tuned `WeightSnapshot`s (rollback) |
| `IngestedFilesRepo` | `ingested_files`     | skip already-ingested files on a resumed run |

> ⚠️ **`DuckDbOpeningBook` is NOT the live opening book.** The running engine
> loads its book from the committed `openings/main-lines.pgn` resource via
> `OpeningBookLoader` (in `EngineBundle.fromResources`, §2.3) — the portable,
> committable variant every serving path uses (`LichessBotMain`,
> `TournamentBotMain`, game-service's `EngineLayer`). `DuckDbOpeningBook` is the
> **offline / alternative** adapter: a data-driven book over the `position_moves`
> table (`BookRepo`) with statistical move weighting. It is **not** wired into
> `EngineBundle` (only exercised in tests) and never touches live play.

---

## 5. Measuring strength (Elo)

This is the part that has been reported inconsistently. **Read this before
quoting an Elo number.**

### 5.1 The one correct method: UCI_Elo-anchored Stockfish

Absolute strength is measured by playing the bot against **Stockfish limited to
a known `UCI_Elo`** (`UCI_LimitStrength = true`), over many games, and reading
the score back as Elo:

```
effective_Elo  ≈  stockfish_UCI_Elo  +  ΔElo(score%)
```

`TournamentMain` does this. Run a small sweep across UCI_Elo levels and find the
~50 % crossover (interpolate; trust scores in the 20–80 % band):

```bash
STOCKFISH_BIN=/opt/homebrew/bin/stockfish \
PICHESS_TOURNAMENT_VS_STOCKFISH=true \
PICHESS_TOURNAMENT_CHALLENGER=8 \
PICHESS_TOURNAMENT_CHALLENGER_HYBRID_ALPHA=0.4 \
PICHESS_TOURNAMENT_CHALLENGER_HYBRID_ALPHA_END=0.6 \
PICHESS_TOURNAMENT_STOCKFISH_ELO=1500 \
PICHESS_TOURNAMENT_GAMES=40 \
PICHESS_TOURNAMENT_DEPTH=4 \
PICHESS_TOURNAMENT_PARALLELISM=1 \
sbt 'botTrain/runMain chess.bot.train.TournamentMain'
# → Tournament(games=40, challenger=…, champion=…, draws=…, score=…%, ΔElo=…)
```

**To measure the *time-managed* config** (not a fixed-depth proxy), set
`PICHESS_TOURNAMENT_BUDGET_MS=2000`: the challenger then plays the same
`bestMoveWithBudget` time-managed search the live bot runs, and the opponent
stays UCI_Elo-anchored. Note the harness uses a **fixed** ms/move budget for
reproducibility, whereas the live bot sizes each move **adaptively** from the
clock (`TimeManager`, §3.1) — so 2000 ms is a representative proxy, not the exact
live budget. This is slower (~2 s per challenger move) so use fewer games.

Rules for a valid measurement:
1. **Anchor with `UCI_Elo`, never `Skill Level`.** Skill levels are a different,
   uncalibrated scale (Skill 5 ≫ UCI_Elo 1320), so they cannot be turned into an
   absolute rating.
2. **Reproduce the *full* production config — not just the evaluator.** ⚠️ The
   harness defaults **every search flag OFF**, but production (`Search.alphaBeta`
   defaults) runs **quiescence, SEE, NMP, singular- and check-extensions ON**. So
   you must set:
   `CHALLENGER=8 CHALLENGER_HYBRID_ALPHA=0.4 CHALLENGER_HYBRID_ALPHA_END=0.6
   CHALLENGER_Q=true CHALLENGER_SEE=true CHALLENGER_NMP=true CHALLENGER_SE=true
   CHALLENGER_FLAGS=checkext` (`…_SEED` already defaults on; α 0.4→0.6 is the
   current depth-6-tuned production blend — see §2.2). Setting only
   `CHALLENGER=8 + HYBRID_ALPHA` — as the first measurements in §5.3 did — rates
   a **crippled** engine: quiescence alone is worth +179 Elo, check-ext +61.
3. **Record the search depth.** Strength is depth-dependent. The harness is
   fixed-depth; the live bot uses a 2 s/move budget (deeper), so it rates a bit
   higher than a depth-4 measurement.
4. **≥ 40 games/level, parallelism 1** (±~50 Elo noise; p>1 is unreliable, §2.1).

### 5.2 Why NOT to quote self-play / relative Elo

Older notes (and commit messages such as *"hybrid ~2500 Elo"*) report **relative
deltas from self-play head-to-heads** (`+74`, `+342`, `+424`, `+120 over
6-shard`). These are useful for "did this change help?" but are **not absolute
ratings**:

- **Self-play inflates.** A net that reliably beats its predecessor scores a big
  ΔElo against *that ancestor*, but a fraction of it against a neutral yardstick
  — it overfits to beating one opponent.
- **Chaining onto an unstated baseline.** Stacking the NNUE deltas onto an
  assumed HCE baseline lands near 2500. The *correctly-measured* production
  config (full search flags, §5.3-B) is **≈2350** at the live time budget —
  so the "2500" was only **~150 Elo high**, within measurement noise plus a
  little self-play inflation. (Caveat about my own process: a first pass measured
  ~2050 and called 2500 "~450 too high" — but that pass was a **crippled** config
  missing quiescence/check-ext; see §5.3. Always reproduce the full production
  flag set, rule #2.)

There is **no recorded calibrated measurement** behind the 2500 figure — it
exists only in a commit message. Treat it as relative, not absolute.

### 5.3 Measured results

> ⚠️ **Two passes — (B) is the real rating.** Pass A set only
> `CHALLENGER=8 + HYBRID_ALPHA=0.3`, which (per rule #2) left **quiescence, SEE,
> NMP and check-extension OFF** — a *crippled* config. Pass B adds the production
> flags. The depth-sensitivity conclusion holds across both; the absolute numbers
> shift **up ~+300**.

**A. Crippled baseline** (`v8 + Hybrid α 0.3`, **no Q/SEE/NMP/check-ext**), 2026-06-09:

| Opponent | Depth | Score | ΔElo | Implied Elo |
|---|---|---|---|---|
| Stockfish `UCI_Elo 1320` | 4 | 83.8 % | +285 | ~1605 |
| Stockfish `UCI_Elo 1500` | 4 | 66.3 % | +117 | ~1617 |
| Stockfish `UCI_Elo 1700` | 4 | 31.3 % | −137 | ~1563 |
| Stockfish `UCI_Elo 1700` | 6 | 70.0 % | +147 | ~1847 |
| Stockfish `UCI_Elo 1900` | 6 | 66.7 % | +120 | ~2020 |
| Stockfish `UCI_Elo 1900` | 2 s/move | 70.0 % | +147 | ~2047 |

→ crippled live config ≈ **2050**. (Depth 4 ≈ 1590, depth 6 ≈ 1900.)

**B. Corrected production baseline** (full flags
`…_Q=…_SEE=…_NMP=…_SE=true …_FLAGS=checkext`), 2026-06-10:

| Matchup | Mode | Score | ΔElo | Result |
|---|---|---|---|---|
| production vs the crippled config (head-to-head) | depth 6 | 66.9 % (39-12-29) | +122 | production is **+122** over crippled |
| production vs Stockfish `UCI_Elo 2200` | **2 s/move** | 70.0 % (10-2-8) | +147 | **≈ 2347** |

**So the real live bot is ≈ 2350 Elo** (±~85, 20-game sample) — about +300 over
the crippled figure, because quiescence + check-extension compound with the
deeper search the 2 s budget buys. It's a *floor*: the tournament uses **no**
opening book (`OpeningBook.Empty`); the live bot has one.

**Robust qualitative finding:** search effort dominates this engine — ~+300 Elo
from depth 4 → 6, and the 2 s budget adds more again.

> Caveats: 20-game absolute samples are ±~85 Elo, and Stockfish's `UCI_Elo`
> calibration is itself approximate — treat ~2350 as ~2300–2400, not a point value.
> This pass measured the then-current `hybridAlpha 0.3` blend; the later depth-6
> re-tune to **0.4→0.6** (§2.2) is a small further gain (self-play +35, SF-depth-6
> +21 — no fresh calibrated re-measurement), so ~2350 is now a slight floor.

---

## 6. Known issues & fixed bugs

- **Budgeted search could return `None` at a legal position → freeze/surrender
  (fixed 2026-06-09).** `budgetedBestMove`'s mate / out-of-budget early-exit
  fired on the *first* iteration by reading a stale TT mate score for the root,
  returning `None` before any iteration ran. It triggered *exactly when losing*
  (a mate score sits in the TT then), which is why the live bot resigned every
  game it didn't win. Fixed so depth-1 always completes first; genuine
  checkmate/stalemate still return `None`. Regression test:
  `BudgetedSearchSpec` → *"returns a legal move even when the TT holds a stale
  mate score for the root"*.
- **Bot used to auto-resign on an empty search (changed 2026-06-09).** `Bridge`
  now never resigns (§3.1).

---

## 7. Full config reference

### Live bot (`bot-lichess`)
`LICHESS_BOT_TOKEN`, `LICHESS_BOT_USERNAME`, `LICHESS_WEIGHTS_VERSION`,
`LICHESS_SEARCH_DEPTH`, `LICHESS_LAZYSMP`, `LICHESS_TABLEBASE` — see
[§3.2](#32-running-it). (The per-move budget is adaptive — `TimeManager`, no env
var.)

### Elo / tournament harness (`bot-train` `TournamentMain`)
`STOCKFISH_BIN` (path to the stockfish binary), `PICHESS_TOURNAMENT_VS_STOCKFISH`,
`PICHESS_TOURNAMENT_STOCKFISH_ELO` (UCI_Elo — the correct anchor),
`PICHESS_TOURNAMENT_STOCKFISH_SKILL` (uncalibrated; avoid for absolute Elo),
`PICHESS_TOURNAMENT_CHALLENGER` (weights version), `…_CHAMPION`,
`…_CHALLENGER_HYBRID_ALPHA` + `…_CHALLENGER_HYBRID_ALPHA_END` (the opening→endgame
NNUE blend; engages the hybrid), `…_CHALLENGER_NNUE_PATH` (A/B an external net),
`PICHESS_TOURNAMENT_BUDGET_MS` (**time-budget mode** — the challenger plays at
N ms/move via `bestMoveWithBudget`, exactly like the live bot, instead of a
fixed depth; opponent unaffected), `…_GAMES`, `…_DEPTH`, `…_PARALLELISM` (use 1
for valid Elo). Per-flag overrides exist for every search heuristic
(`…_CHALLENGER_Q`, `…_SEE`, `…_NMP`, `…_SE`, `…_NNUE`, …).

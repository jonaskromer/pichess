# pichess Bot — Elo Roadmap & Lever Menu

Reconstructed roadmap of Elo-improvement levers for the chess bot, plus the
training/eval recipes and gotchas. Companion to
[`gpu-training-handoff.md`](gpu-training-handoff.md) (the original lived in a
Mac-only Claude memory that wasn't transferred; this rebuilds it). Last updated
2026-06-12 on the RTX 4070 Ti box.

> **⚠ CORRECTION (2026-06-15):** the "+38 raw→TSV fine-tune" reported below was a
> **SELF-PLAY ARTIFACT**. Grounding vs a neutral Stockfish anchor (full-strength
> **SF Skill 20 @ depth 4**) showed **old-eb8 49.4% vs the fine-tuned net 45.6%**
> (400g each) → **eb8 is ~+26 Elo better vs neutral SF**. The fine-tune beat eb8
> head-to-head but is absolutely weaker → **reverted to eb8** (commit eda2a3f).
> LESSON: self-play ΔElo is unreliable here — **rank every eval candidate vs
> full-SF-Skill20-depth4** (~400g). The UCI_Elo-*limited* SF anchor saturates
> (~80%, doesn't bind even at depth 6); full-strength *shallow* SF un-saturates.
> Treat the "+38 / shipping candidate" claims below as struck.

## Current shipped state

- **Eval:** hybrid = `(1-α)·HCE + α·NNUE`, with **phase-tapered α 0.3→0.5** by
  `GamePhase` (`EngineBundle` default `hybridAlphaEndgame=0.5`).
- **HCE:** `weights/v8.json` (Texel-tuned, 690 features, tapered mg/eg).
- **NNUE:** baked `bot-engine/src/main/resources/nnue-v1.bin` = the **eb8** net
  (768→128, endgame-boosted direct-shard, **~3 epochs** of repeated shards
  passes). Adopted at **+19.7 Elo** over the prior deployment.
- **Strength anchor:** pure-HCE depth-4 ≈ **1967 Elo** (vs Stockfish UCI_Elo,
  v8); the hybrid at the live budget is materially stronger.

## Perf + search hardening (committed, pending deploy)

A profiling + hardening pass on the engine (the eval/search levers are otherwise
at their ceiling for the current architecture — see "Tapped levers" below):

- **+13% search speedup, bit-identical.** Profiling the *real* hybrid bot (not
  the HCE-only bench) showed the per-node NNUE accumulator update (`applyDiff`)
  is the hot path, running scalar because of a `Short→Int` width mismatch.
  Widening `featureWeights` to `Int[]` lets C2 auto-vectorize it (also fused
  `evaluateFrom`'s two perspective loops + branchless `screlu`). hybridDepth4
  start 7.6→6.65 ms. Identical eval (`NnueEvaluatorSpec`) → Elo-safe by
  construction. Rejected eval-cache (slower — the incremental eval is too cheap
  to beat a hash lookup). Deferred: explicit `jdk.incubator.vector` SIMD (~10%
  more, but needs the incubator module + a `--add-modules` JVM flag on the live
  bot + a deploy CPU with AVX — deploy-gated).
- **Budgeted-search overshoot fix (live-bot time-safety).** `budgetedBestMove`
  only checked the budget *between* ID iterations — a single deep iteration ran
  to completion unbounded, worst in low-branching endgames where cheap early
  iterations fool the `4×lastIter` projection. That's a real flag-on-time risk.
  Added a per-thread mid-search deadline in `negamax` (node-tick + 1.5×-budget
  cap → abort + unwind, with TT writes guarded so a partial tree can't poison
  the table). Inert at fixed depth. Verified by a `BudgetedSearchSpec`
  regression test + a 200-game budgeted A/B that now finishes in ~4.4 min (was a
  2 h+ hang). Also added matched time-budget self-play to `TournamentMain`
  (budget the champion too) for clean budgeted A/Bs.
- **Allocation pass — depth-6 HCE 53.5→5.0 MB/op (−91%), hybrid 32→15.9 MB/op
  (−50%), all bit-identical.** JMH `-prof gc`/`-prof jfr` (ObjectAllocationSample
  by type, on the *real hybrid* bot) drove six behaviour-preserving GC-pressure
  cuts on the search/move hot path: (1) `Ray.canReach` walks inline, no per-node
  `List[Position]`; (2) repetition via a ply-indexed `long[]` path stack, not a
  per-node `Set[Long]`+boxing; (3) `qSearch`/`searchMoves` move loops inlined, no
  per-node closure + `IntRef`/`BooleanRef`; (4) `BoardState.movePiece` builds the
  post-move board in ONE allocation (was 3 via `- from + (to->piece)`; castling
  6→2, EP 4→2); (5) the TT is now a direct-mapped `AtomicReferenceArray` (key in
  an internal `Slot`) — kills per-probe `Long` boxing AND the old
  `ConcurrentHashMap` eviction-sweep key boxing (this was the biggest single win,
  esp. for the fast HCE path); (6) `BoardState.apply` `getOrElse(throw)` →
  `match` (the by-name arg allocated a `Function0` per call), pooled HCE
  `ArraySink`, and `fillCapturesAndQuiets` packs its two counts into a `Long` (no
  per-node `Tuple2[Int,Int]`). HCE search also got ~32% faster (4.5→3.0 ms/op)
  from the reduced GC. Each guarded by targeted regression tests (determinism
  golden, TT collision-soundness, `movePiece` equivalence) + the incremental-NNUE
  equivalence spec. A follow-up interned `MoveInt.decode`→`Move` via a pre-built
  flyweight table (like `Position.cached`) — the applyMoveInt path's one
  separable alloc (hybrid → 14.9 MB/op). The remaining ~77% was the immutable
  per-move state itself — the apply `Option`/`Some` + `GameState` + `BoardState`.
- **Copy-make search positions — hybrid depth-6 14.7→7.0 MB/op (−52%) and ~6%
  FASTER, bit-identical.** The deferred make/unmake lever, landed as COPY-MAKE:
  per-ply pre-allocated mutable buffers (`MutableBoard` + `SearchPos` in
  `bot-engine/internal`, implementing the domain `BoardLike`/`PositionView` read
  seams) reused as the search recurses — descending into a child copies the
  parent buffer + applies the move in place, so the per-node `BoardState` +
  `GameState` + apply-`Some` all vanish. `SearchPos.copyMakeInto(child,
  moveInt): Boolean` returns legality (NO `null`/sentinel); the gateway
  `Game.applyMove*` Move/Option public API is untouched (the mutable path is
  search-internal only). Threaded through negamax / qSearch / searchMoves + the
  sync & YBWC roots + the NNUE `withAcc`; null move via `copyNullMoveInto`.
  Proven equivalent by `PerftSpec` (re-pointed at copy-make → identical published
  perft counts), a field-level `SearchPos ≡ Game.applyMoveCoreSync` spec, the
  determinism golden, and the incremental-NNUE↔rebuild spec; the dead immutable
  `applyMoveInt` was removed. Re-profiled (JMH `-prof gc`, 2 forks): hybrid d6
  14.68→7.04 MB/op + 58.9→55.2 ms/op, HCE d6 4.98→4.65 MB/op + 2.65→2.43 ms/op —
  both statistically-significant SPEEDUPS (the `PositionView`/`BoardLike` trait
  seam cost nothing). Residual ~7 MB/op hybrid is TT-entry/other alloc — a
  separate lever. At FIXED depth play is bit-identical → the win banks only under
  a TIME budget (faster search → more depth); a timed SF anchor surfaces it.

## Tapped levers (explored + grounded — no further gain found)

- **HCE eval is saturated.** Passed-pawns / king-safety / mobility / PST / etc.
  are already live + tuned in v8 (the earlier "missing passed pawns" note was
  stale). The one extractor feature with no weights — threats — tunes to ~0 when
  activated (v11), i.e. no signal a linear tapered eval can exploit. Gentle
  re-distills (v9 / v10 / v11) stay flat-to-noisy vs v8.
- **Search flags are already well-tuned.** `Search.alphaBeta` is the A/B-tuned
  production config (quiescence / SEE / NMP / singular-ext / check-ext ON with
  measured Elo; counter-move / ID / LMP / aspiration rejected). The remaining
  untested flags don't help this engine: correction-history −207 (broken),
  ordering (multi-ply + history-gravity) +2.9 (noise), RFP −19, razoring+IIR
  −8.7 — all neutral-to-negative at a 100 ms budget. Untried: policy-ordering
  net, LazySMP@2.
- **Deepening the NNUE output head doesn't help.** Added a `(2H)→h2`
  (clipped-ReLU) `→1` float head (the "NNU2" .bin format; the 768→128
  accumulator is unchanged, so per-node `applyDiff` cost stays flat), Scala
  inference parity-verified against the PyTorch forward. The deeper head fits
  the WDL data ~5% better, and that's REAL not overfit (the edge holds at 24M
  rows). But at FIXED depth — its best case, no eval-point speed penalty — it
  plays −8 to −15 Elo vs a linear head trained identically. The fit-gain
  doesn't translate to play: the NNUE is the diluted *minority* hybrid partner
  (α 0.3→0.5, HCE dominant) and lower WDL-loss ≠ better move-ranking. The
  trainer + inference infra works (`--h2` flag, parity harness) but is stashed,
  not committed.

## Confirmed findings (this session, 2026-06-12)

1. **HCE SF-distill was silently broken** — `FenParserRegex` needs 6-field FENs,
   Lichess data is 4-field → empty corpus → tuner returned input unchanged.
   Fixed (`SfDistillMain.normalizeFen` + regression test). **Refutes the old
   "HCE-distill = −120 Elo / compromised" claim** — that was the bug.
2. **HCE re-distill (fixed):** small net gain over v8. SF@2000 anchor (600g):
   v8 45.3% (~1967), v9-processed 47.0% (~1979), v10-raw 49.3% (~1995).
   Processed-vs-raw is within 600g noise; both ≥ v8.
3. **Sequential "raw → gentle TSV fine-tune" (WIN).** Warm-start the baked eb8
   (`--init-from`), then a *gentle* 1-epoch fine-tune on the processed TSV.
   Non-monotonic in LR — A/B vs eb8 in the production hybrid (both HCE v8,
   α 0.3→0.5; only the net differs):
   | fine-tune LR | ΔElo vs eb8 |
   |---|---|
   | 5e-5 | +17.4 (600g) |
   | 1e-4 | **+19.0 (2000g, CI[+7,+31], sig)** |
   | **2e-4** | **+38.4 (2000g, CI[+27,+50], sig) ← peak** |
   | 5e-4 | −40.1 (over-drifts to the bad TSV optimum) |
   So the handoff's "TSV = −120" is about **dose**: from-scratch −120,
   aggressive fine-tune −40, well-dosed gentle fine-tune **+38** (peak at lr2e-4).
   The fine-tuned net
   is a **shipping candidate** (re-validate the full hybrid + SF-anchor before
   adopting).

## Lever menu

> **Deployment constraint (target = live bot VM):** **4 cores — 2 reserved for
> JVM/Docker, only 2 usable by the engine — and 12 GB RAM**; manual/VPN-gated
> redeploy. Implications: net must stay **small + cheap to infer** (cap hidden
> ≈256–384, favour training-side gains over heavy architecture, **no ensemble**);
> **LazySMP capped at ~2 engine threads**; TT + eval-cache must fit 12 GB; and
> **engine SPEED is itself an Elo lever** (more nodes/s → deeper search at the
> live time budget) → see the *Final phase — perf optimization* below.
> **Tried + abandoned (no gain): NNUE ensemble, cleaner/relabelled teacher.**
> (The 24-thread / 32 GB box in *Recipes* is the TRAINING machine, not the target.)

### Eval — NNUE (the bigger lever)
*Architecture (size-bounded by the constraint):*
- **Deepen the 128 (try BEFORE widening)** — add a small output-head layer:
  `concat 256 → Linear 256→H2 → SCReLU → Linear H2→1` (H2≈16–32) instead of the
  current single `256→1`. Adds nonlinearity while the per-node **accumulator
  stays 128-wide → cheaper inference than NNUE-256**. Needs trainer
  (`train_nnue.Net` + `export_bin`) + `NnueEvaluator` (`parse` + `evaluateFrom`)
  + a new quant scale for the extra layer.
- **NNUE-256** — 2× hidden width; simpler change (scale arrays + infer
  `HiddenSize` from `.bin` length `H=(bytes/2−1)/771`) but **2× the per-node
  accumulator cost**. Multi-epoch (~3). Width >~384 likely too costly.
- **Output buckets** — ~8 buckets by piece count (opening/endgame specialise);
  small added inference cost.
- **King-bucketed inputs (HalfKA/HalfKP)** — biggest strength upgrade, but grows
  the feature transformer ~32–64× (~10–15 MB net) + king-move refreshes →
  **borderline vs the RAM budget**; cost-check before committing.

*Training / data (NO inference cost — favoured under the constraint):*
- **WDL-blend targets** — `λ·outcome + (1−λ)·sigmoid(eval)` vs the current
  eval-only; source outcomes via `PgnIngest` / `SelfPlay` / `NnueDataGen`.
- **Self-play / domain-matched data** — positions the bot actually reaches,
  labelled by a deeper search (tooling exists).
- **Horizontal-mirror augmentation** — free 2× data (h-symmetry).
- **Quiet-position filtering** — drop in-check / tactical positions.
- **Gentle TSV fine-tune** — the adopted +38 curriculum; re-apply on top of new nets.
- **Endgame-boost + epoch sweep** — per-epoch A/B for diminishing returns.

### Eval — HCE (smaller, capped lever; cheap inference)
- **Add missing features, then re-distill:** **passed pawns** (rank-scaled —
  the biggest gap, currently absent), attacker-**weighted** king safety (today
  just `king_attackers` count + `pawn_shield`), threats/hanging pieces,
  rook-on-7th, backward pawns, space, pawn mobility. Already has: material, full
  PST, N/B/R/Q mobility, isolated/doubled/connected pawns, `bishop_pair`,
  `knight_outpost`, rook open/semi-open file, `tempo`.
- **Fit K in the distill** — current `K=0.25` saturates ~±40cp (near sign-only);
  a K-search → a magnitude-sensitive distill that moves the weights more usefully.

### Hybrid / cross-cutting (cheapest — do first)
- **α-schedule sweep** — phase-tapered 0.3→0.5 today; endgame Elo reportedly
  still rising at α0.5. **A/B-only, no retrain.** Plus material/phase-bucketed α.
- **Correction history** (`pawncorr` / `matcorr`, off) — learned eval corrections.

### Search (net-independent; all OFF by default in `TournamentMain` → re-A/B)
- **Policy ordering** (`policy` flag) — `PolicyPriorMain` distills SF best-move +
  multi-PV from the processed TSV (`best`/`mpv` cols) → `/policy-prior.bin`.
  Better ordering → more cutoffs → deeper effective search at fixed budget.
- **LazySMP** (`_SMP`) — parallel search, but the target has only **~2 engine
  threads**, so the realistic gain is small (≈1 helper). (The 24-thread box is
  the training machine, not the deploy target.)
- Pruning/extensions: `rfp`, `iir`, `razoring`, `deltaprune`, `movecount`,
  `doubleext`, `multicut`, `nmpverify`, `ttaging`, `pawncorr`/`matcorr`,
  `checkext`; plus `eval-cache` (`_EVCACHE`), aspiration windows, `timemgmt`.

### Data / oracle
- **Syzygy tablebases** (`TbAugmentedSearch`, `_SYZYGY`) — perfect ≤N-piece play.

## Final phase — perf profiling & optimization (after the eval work)

Goal: maximise engine speed (nodes/s) for the **2-engine-core / 12 GB target** —
faster search = deeper search at the live time budget = Elo — **without
regressing any eval Elo gained above.** Loop, ONE optimization at a time:

1. **Profile** on a target-matched config (cap to 2 threads, modest heap): JMH
   `make bench-bot` (`SearchBenchmark` → nodes/s) + async-profiler
   (`make profile-async-cpu` / `-alloc`) for CPU + allocation flamegraphs.
   Likely hot spots: NNUE accumulator/SCReLU + output (vectorize int16?),
   move-gen, TT probe/store, make/unmake, GC/allocation pressure.
2. **Optimize** the top hot path (one change).
3. **Elo-guard A/B (the sanity check):**
   - **fixed-depth A/B vs the pre-change build → must be ≈0 ΔElo.** A pure perf
     change is behaviour-identical at fixed depth; a non-zero ΔElo means it
     altered play = a bug → reject.
   - **nodes/s delta** (JMH) → confirm the speedup is real.
   - **time-budget A/B** (`PICHESS_TOURNAMENT_BUDGET_MS`, the live search path) →
     confirm the speedup converts to +Elo.
4. Keep only changes that are **fixed-depth-neutral AND nodes/s-positive** (and
   ideally time-budget-positive). Repeat. Also: size TT + eval-cache to fit
   12 GB, and set LazySMP helpers for 2 cores.

## Recipes (this box)

- **Env:** `.venv-nnue` (py3.11, torch 2.6.0+cu124, CUDA on the 4070 Ti).
  Shards at `nnue-train/data/shards/` (17, 38 GB, 844,812,067 rows).
- **Parallel TSV build:** `extract_parallel.py` (fans `extract_shards` across
  cores). Processed = `--min-depth 24 --multipv 4` (174.6M rows); raw =
  `--min-depth 0` (342.1M rows).
- **HCE distill:** `JAVA_TOOL_OPTIONS=-Xmx16G` + `PICHESS_SFDISTILL_*`;
  `sbt 'botTrain/runMain chess.bot.train.SfDistillMain <ABS tsv> <ABS out>'`.
  Stride for a uniform 3M-sample draw: `STRIDE ≈ kept_rows / 3M`.
- **NNUE train/fine-tune:** `train_incremental.py`; `--init-from <bin>` warm-
  starts (dequant round-trips exactly); `--tsv` path parallel-parses (fast);
  `--depth-norm 1` ≈ unweighted; per-epoch nets saved when `--epochs>1`.
- **A/B (NNUE):** both sides HCE v8 + `_HYBRID_ALPHA=0.3` `_HYBRID_ALPHA_END=0.5`
  + `_Q/_SEE/_NMP/_SE=true` + `_FLAGS=checkext`; challenger net via
  `_CHALLENGER_NNUE_PATH` (absolute), champion baked. 600g≈±25 Elo, 2000g≈±15.
- **A/B (pure HCE):** as above but NO `_NNUE`/`_HYBRID_ALPHA` → `ArrayTapered`.
- **SF anchor:** `STOCKFISH_BIN=C:\Users\nope\stockfish\...avx2.exe`,
  `_VS_STOCKFISH=true _STOCKFISH_ELO=<n>` (parallelism forced 1).

## Gotchas
- HCE weights load by **filename** `weights/vN.json` from the classpath — run
  `botEngine/Compile/copyResources` after adding one.
- sbt **forked runs use the subproject base as cwd** → pass ABSOLUTE paths.
- Set forked-JVM heap via `JAVA_TOOL_OPTIONS` (the `set ... javaOptions += "..."`
  form gets its quotes stripped by PowerShell).
- **Do not run `sbt scalafmtAll`** — the tree isn't clean under the current
  scalafmt + `core.autocrlf=true`, so it churns ~228 files.
- Read the tournament Elo from the **log line** (`Tournament(... ΔElo=...)`),
  not the exit code.

## Resume / next session

**Banked (2026-06-12):** +38 NNUE adopted as `nnue-v1.bin` on branch
`feat/nnue-rawtsv-finetune` (commits c75b146 fix, f9f80fc adopt). Old eb8 →
`nnue-train/data/nnue-128-eb8-backup.bin`; fine-tune variants + HCE v9/v10 also
in `nnue-train/data/` (gitignored). Env is ready (`.venv-nnue`, shards, both
TSVs). Live redeploy is manual/VPN-gated (not done).

**Open levers, priority order** (constraint: keep the net small/cheap):
1. **α-schedule sweep** — A/B-only, no retrain; endgame Elo still rising at α0.5.
   Cheapest; do first.
2. **NNUE training-side** (no inference cost): WDL-blend targets, self-play /
   domain-matched data, horizontal-mirror augmentation, quiet-position filtering
   — combined with the adopted +38 curriculum.
3. **NNUE capacity (within size budget), in order:** first **deepen the 128**
   (output-head layer, width stays 128 → cheaper inference), then **NNUE-256**
   (width bump) + output buckets. Both need their NnueEvaluator/trainer/quant
   changes; parity-check a fresh-128 via the fast `--tsv` path first; multi-epoch (~3).
4. **Policy priors** — `make policy-prior` + A/B the `policy` flag.
5. **HCE** — add passed-pawns + attacker-weighted king-safety + threats, then
   re-distill with a fitted K (capped upside; cheap inference).
6. **King-bucketed inputs (HalfKA)** — biggest gain but borderline on RAM;
   cost-check first.
7. **Search levers** — re-A/B off-by-default flags, LazySMP (~2 threads),
   eval-cache, Syzygy, correction history.
8. **FINAL: perf profiling/optimization loop** — the allocation pass is DONE
   (six bit-identical cuts + a `MoveInt.decode` flyweight follow-up; depth-6 HCE
   53.5→5.0 MB/op, hybrid 32→14.9; see *Perf + search hardening* above).
   Contained wins are now exhausted. Remaining perf levers: (a) **mutable board
   make/unmake** — the immutable `BoardState`+`GameState`+apply-`Option` is now
   ~77% of hybrid alloc; the only way to cut it (mutate-in-place + Boolean
   legality drops all three), but a large core-model refactor that must keep the
   Move-based public API — deferred pending a plan; (b) **time-budget A/B vs SF**
   to bank the GC speedup as Elo (the cuts are fixed-depth-neutral by
   construction — tests prove identical play).

Excluded (tried, no worthwhile gain): NNUE ensemble, cleaner/relabelled teacher.

**Run rules:** one eval/training job at a time (no CPU/RAM overlap); monitor
long jobs; `JAVA_TOOL_OPTIONS=-Xmx16G` for distill; ABSOLUTE paths for sbt
runMain; never `sbt scalafmtAll` (churns the tree).

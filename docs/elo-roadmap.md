# pichess Bot — Elo Roadmap & Lever Menu

Reconstructed roadmap of Elo-improvement levers for the chess bot, plus the
training/eval recipes and gotchas. Companion to
[`gpu-training-handoff.md`](gpu-training-handoff.md) (the original lived in a
Mac-only Claude memory that wasn't transferred; this rebuilds it). Last updated
2026-06-12 on the RTX 4070 Ti box.

## Current shipped state

- **Eval:** hybrid = `(1-α)·HCE + α·NNUE`, with **phase-tapered α 0.3→0.5** by
  `GamePhase` (`EngineBundle` default `hybridAlphaEndgame=0.5`).
- **HCE:** `weights/v8.json` (Texel-tuned, 690 features, tapered mg/eg).
- **NNUE:** baked `bot-engine/src/main/resources/nnue-v1.bin` = the **eb8** net
  (768→128, endgame-boosted direct-shard, **~3 epochs** of repeated shards
  passes). Adopted at **+19.7 Elo** over the prior deployment.
- **Strength anchor:** pure-HCE depth-4 ≈ **1967 Elo** (vs Stockfish UCI_Elo,
  v8); the hybrid at the live budget is materially stronger.

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

### Eval / net
- **Gentle TSV fine-tune of eb8** (above) — confirmed +19, peak ~+37 near lr2e-4
  (confirming). Cheap, near-term shippable.
- **NNUE-256** — 2× hidden width. Needs `NnueEvaluator` to accept a 256-wide
  `.bin` (infer `HiddenSize` from byte length: `H=(bytes/2−1)/771`) since it's a
  compile-time constant today. Train multi-epoch (match eb8's ~3) to be a fair
  capacity test. The 12 GB GPU + 342M positions can feed it.
- **Endgame-boost / αEnd / endgame_pieces sweeps** — eb8 used boost 8 / α-end
  0.5 / ≤7 pieces; endgame Elo was reportedly still rising at α0.5.
- **NNUE ensemble** — `NnueEnsemble.loadBaked(k=3)` averages 3 nets
  (`_NNUE_ENS` flag wired); cheap if several nets exist.
- **Epoch count** — eb8 = 3 passes; sweep more epochs + A/B per epoch to find
  diminishing returns (per-epoch checkpoints now emitted as `-epN.bin`).

### Search (net-independent; all OFF by default in `TournamentMain` → re-A/B)
- **Policy ordering** (`policy` flag) — `PolicyPriorMain` distills SF best-move +
  multi-PV from the processed TSV (`best`/`mpv` cols) → `/policy-prior.bin`.
  Better ordering → more cutoffs → deeper effective search at fixed budget.
- **LazySMP** (`_SMP`) — parallel search; this box has 24 threads for it.
- Pruning/extensions: `rfp`, `iir`, `razoring`, `deltaprune`, `movecount`,
  `doubleext`, `multicut`, `nmpverify`, `ttaging`, `pawncorr`/`matcorr`,
  `checkext`; plus `eval-cache` (`_EVCACHE`), aspiration windows, `timemgmt`.

### Data / oracle
- **Syzygy tablebases** (`TbAugmentedSearch`, `_SYZYGY`) — perfect ≤N-piece play.

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

**Open levers, priority order:**
1. **NNUE-256.** (a) Make `NnueEvaluator` infer `HiddenSize` from the `.bin`
   length (`H=(bytes/2−1)/771`) so a 256-wide net loads. (b) Parity-check the
   fast path: train a fresh **128** via `--tsv lichess-eval-raw.tsv.gz
   --depth-norm 1 --endgame-boost 8 --epochs 3 --hidden 128` → A/B vs eb8
   (expect ≈0). (c) Train **256** the same way; then apply the +38 curriculum
   (gentle TSV fine-tune, `--init-from` + lr 2e-4 × 1 epoch) on top; A/B + (try
   to) anchor.
2. **Policy priors.** `make policy-prior` (PolicyPriorMain → `/policy-prior.bin`
   from the processed TSV); A/B the `policy` search flag.
3. **Search-flag re-A/B, LazySMP, eval-cache, Syzygy** (see lever menu).

**Run rules:** one eval/training job at a time (no CPU/RAM overlap); monitor
long jobs; `JAVA_TOOL_OPTIONS=-Xmx16G` for distill; ABSOLUTE paths for sbt
runMain; never `sbt scalafmtAll` (churns the tree).

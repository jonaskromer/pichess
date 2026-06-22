# GPU Training Handoff — pichess NNUE / hybrid-eval

Self-contained guide to continue the NNUE + hybrid-eval work on a **CUDA GPU box**.
MPS on the Mac capped training at ~140K rows/s (single-thread parse-bound); a real
GPU should be multiples faster. **Everything that determines results is committed to
git**; only the large datasets are not (re-fetch or rsync).

## TL;DR
```bash
git clone https://github.com/jonaskromer/pichess.git && cd pichess
python3.12 -m venv .venv-nnue && .venv-nnue/bin/pip install --upgrade pip
# CUDA torch (match your driver, e.g. cu124); then the rest:
.venv-nnue/bin/pip install torch --index-url https://download.pytorch.org/whl/cu124
.venv-nnue/bin/pip install numpy==2.4.6 pyarrow==24.0.0 huggingface_hub hf-xet tqdm
# get the 38 GB shards (rsync from the Mac, or re-download — see §3), then verify:
.venv-nnue/bin/python -u nnue-train/train_incremental.py --out /tmp/eb8-verify.bin \
  --shards 17 --rows-per-shard 0 --local-shards nnue-train/data/shards \
  --batch 16384 --lr 0.003 --wd 5e-5 --lr-decay 0.9 --hidden 128 \
  --endgame-pieces 7 --endgame-boost 8     # first log line should read: device=cuda
```

## 1. Code (git)
`git clone https://github.com/jonaskromer/pichess.git` carries the trainer, extractor,
eval, A/B harness, **and the adopted net** (`bot-engine/src/main/resources/nnue-v1.bin`
= the endgame-boosted "eb8" net). The CUDA device-detection is already committed (it
auto-picks `cuda`→`mps`→`cpu`), so no manual edit is needed. Key recent commits:
- `8a15370` phase-tapered hybrid α + endgame-boosted NNUE — +19.7 Elo
- `732b0dd` tournament `--openings-file` + per-side `HYBRID_ALPHA_END`
- `3bbca36` endgame-boost (`parse_batch_eb`) + `--local-shards` + `--per-analysis`/`--local-dir`

## 2. Python env
**Python 3.12.** Mac had: torch 2.12.0, numpy 2.4.6, pyarrow 24.0.0, hf-xet 1.5.1,
tqdm 4.68.1. On the GPU box install the **CUDA** torch build (see TL;DR). The trainer
prints `device=cuda hidden=128` on line 1 when CUDA is live — check that.

## 3. Data — 17 shards, 38 GB (NOT in git)
**Option A — rsync from the Mac** (fastest if reachable):
```bash
rsync -av --progress <mac-host>:/Users/juu/projects/pichess/nnue-train/data/shards/ \
  nnue-train/data/shards/
# optional: the derived nets too (193 KB each) to skip retraining:
rsync -av <mac-host>:/Users/juu/projects/pichess/nnue-train/data/'nnue-128-*.bin' nnue-train/data/
```
**Option B — re-download from HuggingFace** (robust, resumable; ~hours on a slow link):
```bash
mkdir -p nnue-train/data/shards
BASE=https://huggingface.co/datasets/Lichess/chess-position-evaluations/resolve/main/data
for si in $(seq -w 0 16); do
  f=$(printf nnue-train/data/shards/train-%05d-of-00017.parquet "$si")
  url=$(printf "$BASE/train-%05d-of-00017.parquet" "$si")
  wget -c -t 0 --timeout=30 --waitretry=5 --retry-connrefused -O "$f" "$url"
done
```
Verify: 17 files, ~38 GB, **844,812,067** total rows (each parquet footer readable).
Only the shards are needed to regenerate everything; the big TSVs
(`lichess-{clean,eval}.tsv.gz`) and nets are derived/regenerable.

## 4. Proven recipes

### Adopted net (endgame-boost, direct-shard) — reproduce to confirm parity
```bash
.venv-nnue/bin/python -u nnue-train/train_incremental.py \
  --out nnue-train/data/nnue-128-eb8.bin \
  --shards 17 --rows-per-shard 0 --local-shards nnue-train/data/shards \
  --batch 16384 --lr 0.003 --wd 5e-5 --lr-decay 0.9 --hidden 128 \
  --endgame-pieces 7 --endgame-boost 8
```
Trains on all 342M positions → **197378-byte** net, weighted loss ~0.026. ~80 min on
MPS; much faster on CUDA. (Drop `--endgame-pieces/--endgame-boost` for the plain
shipped-equivalent "repro" recipe; that scores ~0 vs the old net = pipeline is sound.)

### A/B vs the baked net (general + endgame pools)
Both sides HCE v8, production search base, 600 games, depth 4, parallelism 8.
Challenger = your net via `CHALLENGER_NNUE_PATH`; champion = baked (omit its path).
The **adopted config tapers α 0.3→0.5**:
```bash
env PICHESS_TOURNAMENT_CHALLENGER=8 PICHESS_TOURNAMENT_CHAMPION=8 \
  PICHESS_TOURNAMENT_CHALLENGER_Q=true  PICHESS_TOURNAMENT_CHAMPION_Q=true \
  PICHESS_TOURNAMENT_CHALLENGER_SEE=true PICHESS_TOURNAMENT_CHAMPION_SEE=true \
  PICHESS_TOURNAMENT_CHALLENGER_NMP=true PICHESS_TOURNAMENT_CHAMPION_NMP=true \
  PICHESS_TOURNAMENT_CHALLENGER_SE=true  PICHESS_TOURNAMENT_CHAMPION_SE=true \
  PICHESS_TOURNAMENT_CHALLENGER_FLAGS=checkext PICHESS_TOURNAMENT_CHAMPION_FLAGS=checkext \
  PICHESS_TOURNAMENT_GAMES=600 PICHESS_TOURNAMENT_DEPTH=4 PICHESS_TOURNAMENT_PARALLELISM=8 \
  PICHESS_TOURNAMENT_CHALLENGER_NNUE_PATH=$PWD/nnue-train/data/nnue-128-eb8.bin \
  PICHESS_TOURNAMENT_CHALLENGER_HYBRID_ALPHA=0.3 \
  PICHESS_TOURNAMENT_CHALLENGER_HYBRID_ALPHA_END=0.5 \
  PICHESS_TOURNAMENT_CHAMPION_HYBRID_ALPHA=0.3 \
  sbt -batch 'set botTrain/Compile/run/javaOptions += "-Xmx6G"' \
      "botTrain/runMain chess.bot.train.TournamentMain"
```
Result line: `Tournament(games=…, score=…%, ΔElo=±…)` (challenger − champion; read the
log, not the exit code — a trailing `; echo` masks sbt's rc).
- **Endgame pool:** add `PICHESS_TOURNAMENT_OPENINGS_FILE=<fenfile>` — one FEN per line,
  6-field. Build a balanced-endgame pool by scanning shards for `≤12 pieces, |cp|≤200,
  depth≥18` and appending ` 0 1` to each 4-field FEN.
- **Run tournaments SEQUENTIALLY** — contested CPU corrupts any time/budget-based eval
  (fixed-depth is result-safe but go one-at-a-time anyway).

### SF absolute-Elo anchor
`PICHESS_TOURNAMENT_VS_STOCKFISH=true PICHESS_TOURNAMENT_STOCKFISH_ELO=<n>` (Stockfish
on PATH; `apt install stockfish` on Linux), fixed depth, parallelism forced to 1.
Pick the SF UCI_Elo so the bot scores ~50% — SF@2500 ceilinged at ~80% (too weak).

## 5. State — what's settled (don't re-derive)
- **Adopted & in-repo:** eb8 net + phase-tapered α (by `GamePhase`), **+19.7 Elo /
  2.9σ (1500g)** vs the prior deployment. α was the **0.3→0.5** blend when eb8 was
  A/B'd (recipe below); it has **since been re-tuned to the shipping 0.4 → 0.6** —
  `EngineBundle` now defaults `hybridAlpha=0.4` / `hybridAlphaEndgame=0.6`.
- **REFUTED (don't repeat):** data refinement via the depth-weighted **TSV** pipeline —
  refined / uniform / clean per-analysis nets all ~−120 Elo standalone. The shipped
  direct-shard recipe (`parse_batch`, unweighted, 1 pass) reproduces ~0 → the trainer
  is sound; the **TSV extraction recipe** was the regression. HCE-v9 distill reads the
  same TSV → likely also compromised; deprioritize. *(Update: `elo-roadmap.md`
  Findings #1/#3 later traced this −120 to a FEN 4-vs-6-field bug in the TSV reader —
  not the data itself — after which re-distilled HCE nets improved; check there
  before deprioritizing.)*
- **The lever that worked was EVAL-side, not data:** endgame up-weighting + phase-tapered α.

## 6. Open agenda (all build on the WORKING direct-shard recipe)
- **boost / αEnd / endgame_pieces sweeps** — eb8 used 8 / 0.5 / 7; endgame Elo was still
  rising at α0.5, so there's likely more.
- **NNUE-256 capacity** — `--hidden 256`; needs a `HiddenSize=256` Scala build
  (`NnueEvaluator.scala`; `parse` validates the byte size).
- **Net-independent levers** — LazySMP, search-flag re-A/B, eval-cache (see
  `project-bot-elo-roadmap` in memory).

## 7. Claude memory (for an AI assistant to continue with full context)
~176 KB of markdown at `~/.claude/projects/-Users-juu-projects-pichess/memory/`. rsync
the `*.md` into the GPU box's matching `~/.claude/projects/<its-path-to-pichess>/memory/`
(the dir name is the project path with `/`→`-`; **include `MEMORY.md`** — the index loaded
each session). Most relevant: `project-retrain-session-state.md` (full arc + current
state), `project-bot-elo-roadmap.md`, `feedback-*.md` (preferences + gotchas).

## Gotchas
- The tournament forks a fresh JVM (`Compile/run/fork := true`) inheriting the launch
  env — so per-call env vars work; run each tournament as its own process.
- `git pull` already carries the eb8 net (it's a tracked resource); don't re-bake unless retraining.
- Live-bot redeploy is manual (VPN-gated VM) — the committed bot is ready when you want it live.

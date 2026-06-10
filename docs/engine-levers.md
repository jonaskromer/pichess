# piChess — Engine Strength Levers

Reference for the bot's strength levers, the shared training pipeline, and the
validation strategy. Companion to [`bot.md`](bot.md) (Elo methodology + results).

Status as of 2026-06-10. **Almost everything below is built but not yet
A/B-validated** — see [Validation strategy](#validation-strategy).

---

## Architecture in one breath

- **Eval:** HCE + NNUE **hybrid**. The HCE is a tapered (`_mg`/`_eg` by material
  `GamePhase`) linear evaluator over the v8 tuned weights; the NNUE is a
  Stockfish-distilled `(768→128)×2→1` perspective net (~193 KB). Blended at
  `hybridAlpha = 0.3` (NNUE weight).
- **Search:** alpha-beta + TT, quiescence, SEE, null-move pruning, singular &
  check extensions, MVV-LVA / killer / history / counter-move ordering,
  **incremental NNUE accumulators**, optional **LazySMP**.
- **Live (Lichess):** per-game **isolated** search, **clock-aware** time
  management, optional **7-piece tablebase** oracle, **weighted-random opening
  book**. Never resigns; never flags.

---

## Shared data pipeline

One download feeds every data-driven lever — no re-pulling 40 GB per experiment.

```
make nnue-data        # download 17 Lichess shards once → ONE gzipped TSV
                      #   fen \t cp \t mate \t best \t depth \t knodes \t mpv
```
- Producer: `nnue-train/extract_shards.py` (stream → dedup per FEN → depth-filter).
- Scala reader: `chess.bot.train.LichessEvalReader`.
- Consumers: NNUE retrain (6b), HCE distill (7b), policy priors (4b).
- The TSV is **not** committed (large); the *trained artifacts* (~193 KB net,
  weights JSON, policy `.bin`) are committable.

---

## Levers

| # | Lever | What it does | Activate | Validated? |
|---|-------|--------------|----------|------------|
| **1** | Incremental NNUE | maintain per-color accumulators across make/unmake → ~1.7× faster eval, byte-identical to rebuild | on by default | speed proven; Elo A/B pending |
| **2** | LazySMP | time-budgeted multi-thread search; global `ParallelismBudget` (non-blocking, main always runs); per-game isolated | `LICHESS_LAZYSMP` (default on) | thread-safe (stress test); Elo A/B pending |
| **3** | Eval cache | cache `evaluateWith` by Zobrist; **footgun fixed** (used to silently disable incremental) | `evalCacheEnabled` (off) | marginal w/ incremental; expect ~neutral |
| **5** | Time management | size each move to the Lichess clock; never flags (`TimeManager.budgetMs`) | live (replaced flat 2 s) | unit-proven (never-flag); live |
| **8** | Tablebases | perfect ≤7-piece via Lichess API (`LichessTablebaseSearch`), fail-safe to search | `LICHESS_TABLEBASE` (default on) | live-validated; **being replaced by local net** (see below) |
| **4b** | Policy priors | SF best-move from→to butterfly priors boost quiet-move ordering | `make policy-prior` → `CHALLENGER_FLAGS=policy` | needs data + A/B |
| **6b** | NNUE retrain | depth- & endgame-weighted training from the shared TSV | `make nnue-retrain` (`--endgame-boost`) | needs data + A/B |
| **7b** | HCE distill | re-tune HCE to SF evals (sharpens `_eg` with endgame boost) | `make hce-distill` (`ENDGAME_BOOST`) | needs data + A/B |
| **9** | Opening book | weighted-random pick over 79 main lines (variety + coherent) | live | live |

Search flags are A/B'd via `TournamentMain` env (`CHALLENGER_FLAGS=...`); see the
[measurement gotcha](#measurement-gotcha).

---

## Local endgames & openings (no external API, no huge DB)

Constraint: everything local + committable (see memory `feedback-local-committable`).

- **Endgames:** instead of the runtime tablebase API or shipping Syzygy files,
  **train the eval to play endgames** — the dataset's ≤7-piece SF labels are
  already near-tablebase-perfect. Both trainers up-weight sparse positions:
  - NNUE: `train_incremental.py --tsv … --endgame-boost N --endgame-pieces 7`
  - HCE: `PICHESS_SFDISTILL_ENDGAME_BOOST=N make hce-distill` (sharpens `_eg`)
  - **Honest limit:** a net is "very good," *not* tablebase-perfect on the
    hardest technical endings (KBNvK, fortresses, exact 50-move DTZ).
  - The runtime tablebase API stays a **stopgap** behind `LICHESS_TABLEBASE`;
    drop it once the endgame-trained net is A/B-validated.
- **Openings:** the committable `main-lines.pgn` (79 lines, ~9 KB) + the eval.
  No database. The bot already differentiates phase: HCE tapers `_mg`/`_eg` by
  material; the search disables null-move pruning with no non-pawn material
  (zugzwang guard).

---

## Activation flow (data-driven levers)

```
make nnue-data                                              # 40 GB once → shared TSV
PICHESS_SFDISTILL_ENDGAME_BOOST=6 make hce-distill          # → weights/v9.json (eg-sharpened)
make nnue-retrain ENDGAME_BOOST=… (--endgame-boost 6)       # → nnue-v1.bin (endgame-aware)
make policy-prior                                           # → /policy-prior.bin
# then A/B each at the live budget; adopt winners; re-tune hybridAlpha LAST
```

---

## Validation strategy

Everything is **built but unvalidated**. Validation is gated on the data run,
because A/B outcomes are entangled with eval quality. Three buckets:

1. **The retrains *are* the data** (4b / 6b / 7b / endgame / bigger net) — no
   artifact exists until `make nnue-data` runs; untestable, not false negatives.
2. **Value scales with eval quality/cost → false-negative risk now**
   - Eval cache (#3): payoff grows with eval cost → bigger net flips it.
   - `hybridAlpha` (#7): optimum *shifts* with NNUE strength (already moved
     0.5→0.3 with the SF net) — re-tune on the **final** net.
   - **Eval-margin prunings** (RFP, razoring, delta, futility): key on
     `static_eval ± margin`; a sharper eval makes them work.
3. **Data-independent → validate now (as floors)**: incremental NNUE (#1),
   LazySMP (#2), time management (#5), and ordering/shape heuristics (LMR,
   killers, check ext).

**Decision rule (asymmetric):** keep positive *feature flags* (a feature that
helps a weak eval usually helps a strong one); **re-tune scalars** (`hybridAlpha`,
margins) on the final net regardless; **re-test negatives** after the data run —
especially the eval-margin prunings.

**Axed but might flip (roadmap #4):** the eval-margin prunings were turned off
after a **depth-4** sweep against an **HCE-ceiling** eval — a double-whammy
(prunings need depth *and* a trustworthy eval). Re-A/B at the live budget on the
retrained net is exactly where they may turn positive.

**Sequence:** run the pipeline first → train the best net/weights → *then* A/B on
that foundation → re-tune `hybridAlpha` last.

---

## Measurement gotcha

`TournamentMain` defaults **every** search flag OFF. To reproduce production set:
`CHALLENGER_Q=true CHALLENGER_SEE=true CHALLENGER_NMP=true CHALLENGER_SE=true
CHALLENGER_FLAGS=checkext CHALLENGER=8 CHALLENGER_HYBRID_ALPHA=0.3`. Use
`PICHESS_TOURNAMENT_BUDGET_MS` to A/B at the live time budget. For LazySMP, A/B at
game-parallelism 1 + a budget (the parallel tournament already saturates cores).

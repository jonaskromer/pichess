#!/usr/bin/env bash
# A/B sweep harness — re-A/B each off-by-default search flag AND the session's
# perf levers at the LIVE time budget, against a faithfully-reproduced
# production baseline, and print a table tagged keep / provisional-eval /
# provisional-depth.
#
# Why: many flags were turned off after a DEPTH-4 sweep against an HCE-ceiling
# eval (docs/engine-levers.md → Validation strategy). This re-tests them at the
# budget the bot actually plays. Reading: a POSITIVE result on a feature is a
# keeper; a NEGATIVE on a *-provisional item should be RE-RUN after the data
# retrain (its value scales with eval quality/cost). Re-tune HYBRID_ALPHA last.
#
# Drives TournamentMain only. One item = one tournament. Expect hours at
# GAMES=200; lazysmp runs at PARALLELISM=1 (serial games) so it is ~Nx slower —
# use a smaller GAMES for it.
#
# Usage:
#   scripts/ab-sweep.sh                      # full sweep, 200 games, 2s budget
#   GAMES=20 scripts/ab-sweep.sh             # quick smoke (noisy; checks plumbing)
#   FLAGS="rfp lazysmp incremental" scripts/ab-sweep.sh   # only these items
set -euo pipefail
cd "$(dirname "$0")/.."

GAMES="${GAMES:-200}"
BUDGET_MS="${BUDGET_MS:-2000}"
WEIGHTS="${WEIGHTS:-8}"          # production HCE backbone
ALPHA="${ALPHA:-0.3}"           # production hybrid blend
PARALLELISM="${PARALLELISM:-4}" # game-level parallelism (lazysmp forces 1)
OUT="${OUT:-/tmp/pichess-ab-sweep.tsv}"
BASE_FLAGS="checkext"           # production default ON; the shared baseline
SELECT="${FLAGS:-}"

# Production baseline: BOTH sides get the production search config (the
# measurement gotcha — TournamentMain defaults every flag OFF).
export PICHESS_TOURNAMENT_CHALLENGER="$WEIGHTS"  PICHESS_TOURNAMENT_CHAMPION="$WEIGHTS"
export PICHESS_TOURNAMENT_CHALLENGER_Q=true      PICHESS_TOURNAMENT_CHAMPION_Q=true
export PICHESS_TOURNAMENT_CHALLENGER_SEE=true    PICHESS_TOURNAMENT_CHAMPION_SEE=true
export PICHESS_TOURNAMENT_CHALLENGER_NMP=true    PICHESS_TOURNAMENT_CHAMPION_NMP=true
export PICHESS_TOURNAMENT_CHALLENGER_SE=true     PICHESS_TOURNAMENT_CHAMPION_SE=true
export PICHESS_TOURNAMENT_CHALLENGER_HYBRID_ALPHA="$ALPHA" PICHESS_TOURNAMENT_CHAMPION_HYBRID_ALPHA="$ALPHA"
export PICHESS_TOURNAMENT_BUDGET_MS="$BUDGET_MS"
export PICHESS_TOURNAMENT_GAMES="$GAMES"

# Search flags (the axed prunings/extensions), "flag:tag". Tags drive the
# post-data re-test: provisional-eval keys on static_eval±margin; provisional-
# depth prunes/extends the tree; keep is ordering/shape (eval-robust).
ALL_FLAGS=(
  "rfp:provisional-eval"        "razoring:provisional-eval"   "deltaprune:provisional-eval"
  "movecount:provisional-depth" "multicut:provisional-depth"  "doubleext:provisional-depth"
  "iir:keep"                    "nmpverify:keep"              "histgravity:keep"
  "ttaging:keep"                "multiply:keep"              "underpromo:keep"
  "pawncorr:provisional-eval"   "matcorr:provisional-eval"   "policy:keep"
  "timemgmt:keep"
)

printf 'item\ttag\tscore%%\tdElo\n' > "$OUT"
printf '%-16s %-18s %8s %8s\n' "item" "tag" "score%" "ΔElo"
printf '%-16s %-18s %8s %8s\n' "----" "---" "------" "----"

selected() { [[ -z "$SELECT" || " $SELECT " == *" $1 "* ]]; }

# Reset the per-test deltas back to the shared baseline between runs.
reset_deltas() {
  export PICHESS_TOURNAMENT_CHALLENGER_FLAGS="$BASE_FLAGS"
  export PICHESS_TOURNAMENT_CHAMPION_FLAGS="$BASE_FLAGS"
  export PICHESS_TOURNAMENT_PARALLELISM="$PARALLELISM"
  unset PICHESS_TOURNAMENT_CHALLENGER_SMP PICHESS_TOURNAMENT_CHALLENGER_EVCACHE \
        PICHESS_TOURNAMENT_CHALLENGER_INCREMENTAL PICHESS_TOURNAMENT_CHAMPION_INCREMENTAL
}

run_one() {  # $1=label $2=tag  (caller has exported the challenger/champion delta)
  local log="/tmp/pichess-ab-$1.log" line
  sbt -batch "botTrain/runMain chess.bot.train.TournamentMain" > "$log" 2>&1 || true
  line="$(grep -oE 'Tournament\(games=[^)]*\)' "$log" | tail -1)"
  local score delo
  score="$(sed -nE 's/.*score=([0-9.]+)%.*/\1/p' <<<"$line")"
  delo="$(sed -nE 's/.*ΔElo=([+-][0-9.]+).*/\1/p' <<<"$line")"
  printf '%-16s %-18s %8s %8s\n' "$1" "$2" "${score:-?}" "${delo:-FAILED}"
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "${score:-?}" "${delo:-FAILED}" >> "$OUT"
}

# --- search flags (challenger = baseline + flag vs champion = baseline) ---
for entry in "${ALL_FLAGS[@]}"; do
  flag="${entry%%:*}"; tag="${entry##*:}"
  selected "$flag" || continue
  reset_deltas
  export PICHESS_TOURNAMENT_CHALLENGER_FLAGS="$BASE_FLAGS,$flag"
  run_one "$flag" "$tag"
done

# --- perf levers (dedicated env; positive ΔElo = the lever helps) ---
if selected incremental; then
  reset_deltas
  export PICHESS_TOURNAMENT_CHAMPION_INCREMENTAL=false   # challenger keeps default ON
  run_one incremental keep
fi
if selected lazysmp; then
  reset_deltas
  export PICHESS_TOURNAMENT_CHALLENGER_SMP=true
  export PICHESS_TOURNAMENT_PARALLELISM=1                # 1 game → it grabs all cores
  run_one lazysmp keep
fi
if selected evalcache; then
  reset_deltas
  export PICHESS_TOURNAMENT_CHALLENGER_EVCACHE=true
  run_one evalcache provisional-eval
fi

echo
echo "Done. Table → $OUT"
echo "Positive feature/perf items are keepers; NEGATIVE *-provisional items"
echo "should be RE-RUN after 'make nnue-data' + retrain. Re-tune HYBRID_ALPHA"
echo "last, on the final net. (lazysmp ran at PARALLELISM=1 — serial games.)"

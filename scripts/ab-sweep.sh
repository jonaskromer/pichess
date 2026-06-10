#!/usr/bin/env bash
# A/B sweep harness — re-A/B each off-by-default search flag at the LIVE time
# budget, against a faithfully-reproduced production baseline, and print a table
# tagged keep / provisional-eval / provisional-depth.
#
# Why: many flags were turned off after a DEPTH-4 sweep against an HCE-ceiling
# eval (see docs/engine-levers.md → Validation strategy). This re-tests them at
# the budget the bot actually plays, where the eval-margin prunings have their
# best shot. Asymmetric reading: a POSITIVE result on a feature flag is a keeper;
# a NEGATIVE on a *-provisional flag should be RE-TESTED after the data run
# (`make nnue-data` → retrain) because its value scales with eval quality.
#
# This script only DRIVES TournamentMain; it runs no engine logic itself.
# Each flag = one tournament (challenger = production+flag vs champion =
# production). Expect hours at GAMES=200 — tune GAMES / FLAGS for a smaller pass.
#
# Usage:
#   scripts/ab-sweep.sh                 # full sweep, 200 games/flag, 2s budget
#   GAMES=20 scripts/ab-sweep.sh        # quick smoke (noisy, just checks plumbing)
#   FLAGS="rfp razoring" scripts/ab-sweep.sh   # only these
set -euo pipefail
cd "$(dirname "$0")/.."

GAMES="${GAMES:-200}"
BUDGET_MS="${BUDGET_MS:-2000}"
WEIGHTS="${WEIGHTS:-8}"          # production HCE backbone
ALPHA="${ALPHA:-0.3}"           # production hybrid blend
OUT="${OUT:-/tmp/pichess-ab-sweep.tsv}"

# Production baseline: BOTH sides get the production search config + checkext
# (the measurement gotcha — TournamentMain defaults every flag OFF).
export PICHESS_TOURNAMENT_CHALLENGER="$WEIGHTS"  PICHESS_TOURNAMENT_CHAMPION="$WEIGHTS"
export PICHESS_TOURNAMENT_CHALLENGER_Q=true      PICHESS_TOURNAMENT_CHAMPION_Q=true
export PICHESS_TOURNAMENT_CHALLENGER_SEE=true    PICHESS_TOURNAMENT_CHAMPION_SEE=true
export PICHESS_TOURNAMENT_CHALLENGER_NMP=true    PICHESS_TOURNAMENT_CHAMPION_NMP=true
export PICHESS_TOURNAMENT_CHALLENGER_SE=true     PICHESS_TOURNAMENT_CHAMPION_SE=true
export PICHESS_TOURNAMENT_CHALLENGER_HYBRID_ALPHA="$ALPHA" PICHESS_TOURNAMENT_CHAMPION_HYBRID_ALPHA="$ALPHA"
export PICHESS_TOURNAMENT_BUDGET_MS="$BUDGET_MS"
export PICHESS_TOURNAMENT_GAMES="$GAMES"

BASE_FLAGS="checkext"   # production default ON; the baseline both sides share

# Test matrix: "flag:tag". Tags drive the post-data re-test decision.
#   provisional-eval  — keys on static_eval ± margin → re-test after retrain
#   provisional-depth — prunes/extends the tree → benefits from the live depth
#   keep              — ordering/shape; eval-robust, bank if positive
ALL_FLAGS=(
  "rfp:provisional-eval"        "razoring:provisional-eval"   "deltaprune:provisional-eval"
  "movecount:provisional-depth" "multicut:provisional-depth"  "doubleext:provisional-depth"
  "iir:keep"                    "nmpverify:keep"              "histgravity:keep"
  "ttaging:keep"                "multiply:keep"              "underpromo:keep"
  "pawncorr:provisional-eval"   "matcorr:provisional-eval"   "policy:keep"
  "timemgmt:keep"
)

# Optional restriction via FLAGS="rfp razoring ..."
SELECT="${FLAGS:-}"

printf 'flag\ttag\tscore%%\tdElo\n' > "$OUT"
printf '%-14s %-18s %8s %8s\n' "flag" "tag" "score%" "ΔElo"
printf '%-14s %-18s %8s %8s\n' "----" "---" "------" "----"

for entry in "${ALL_FLAGS[@]}"; do
  flag="${entry%%:*}"; tag="${entry##*:}"
  if [[ -n "$SELECT" && ! " $SELECT " == *" $flag "* ]]; then continue; fi

  export PICHESS_TOURNAMENT_CHAMPION_FLAGS="$BASE_FLAGS"
  export PICHESS_TOURNAMENT_CHALLENGER_FLAGS="$BASE_FLAGS,$flag"

  log="/tmp/pichess-ab-$flag.log"
  sbt -batch "botTrain/runMain chess.bot.train.TournamentMain" > "$log" 2>&1 || true

  line="$(grep -oE 'Tournament\(games=[^)]*\)' "$log" | tail -1)"
  score="$(sed -nE 's/.*score=([0-9.]+)%.*/\1/p' <<<"$line")"
  delo="$(sed -nE 's/.*ΔElo=([+-][0-9.]+).*/\1/p' <<<"$line")"
  score="${score:-?}"; delo="${delo:-FAILED}"

  printf '%-14s %-18s %8s %8s\n' "$flag" "$tag" "$score" "$delo"
  printf '%s\t%s\t%s\t%s\n' "$flag" "$tag" "$score" "$delo" >> "$OUT"
done

echo
echo "Done. Table → $OUT"
echo "Reading: positive feature flags are keepers; NEGATIVE *-provisional flags"
echo "should be RE-RUN after 'make nnue-data' + retrain (their value scales with"
echo "eval quality/depth). Re-tune HYBRID_ALPHA last, on the final net."

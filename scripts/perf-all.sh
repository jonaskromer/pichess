#!/usr/bin/env bash
#
# perf-all.sh — orchestrate the full piChess performance test suite.
#
# Layers run, in order:
#   2.  JMH microbenchmarks               (no stack — pure-JVM)
#   1.  Gatling cross-backend harness     (rotates BACKENDS itself)
#   1b. k6 browser surface                (postgres stack + obs)
#
# Layers 3 (zio-profiling) and 4 (async-profiler) are investigative and
# stay out — they attach to a running service for a specific question,
# not as a sweep. Layers 5/6 (Prometheus / OTel) come up alongside the
# Gatling run via OBS=true.
#
# Env vars are forwarded straight through to the sub-tools:
#   BACKENDS, MODE, PEAK_USERS, RAMP_SECONDS, HOLD_SECONDS, RATE_PER_SEC
#     → scripts/perf-run.sh
#   K6_VUS, K6_DURATION
#     → scripts/k6-run.sh
#   OBS  — default `true` for perf-all (richer report);
#          set OBS=false to skip Prometheus snapshots.
#
# A trap ensures the stack is torn down even on failure or Ctrl-C.

set -euo pipefail

OBS="${OBS:-true}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

GATEWAY_URL="http://localhost:8090"

log() { printf '\n[\033[1;35mperf-all\033[0m %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

cleanup() {
  log "tearing stack down"
  make stack-down >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_url() {
  local url="$1" tries="${2:-60}"
  while ((tries > 0)); do
    if curl -sf -o /dev/null "$url"; then return 0; fi
    sleep 1
    ((tries--))
  done
  echo "timed out waiting for $url" >&2
  return 1
}

log "─── piChess perf suite — full run ───────────────────────"

# ── Layer 2 — JMH microbenchmarks ──────────────────────────────────────
log "Layer 2 / 3 — JMH microbenchmarks (no stack required)"
make bench

# ── Layer 1 — Gatling cross-backend ────────────────────────────────────
log "Layer 1 — Gatling cross-backend (BACKENDS=${BACKENDS:-default} MODE=${MODE:-Game} OBS=$OBS)"
OBS="$OBS" make perf

# ── Layer 1b — k6 browser surface ──────────────────────────────────────
# perf-run.sh tears the stack down on completion, so bring postgres back
# up for k6. Browser perf is backend-agnostic; postgres is the most
# representative single choice.
log "Layer 1b — k6 browser surface (postgres stack)"
if [[ "$OBS" == "true" ]]; then
  make stack-postgres EXTRA=obs
else
  make stack-postgres
fi
wait_for_url "$GATEWAY_URL/api/stack-info" 120
make k6-browser

# ── Summary ────────────────────────────────────────────────────────────
latest_run="$(ls -dt perf-reports/*/ 2>/dev/null | grep -v 'bench-' | head -1 || true)"
latest_bench="$(ls -t perf-reports/bench-*.json 2>/dev/null | head -1 || true)"
log "─── suite complete ──────────────────────────────────────"
echo "  JMH results       : ${latest_bench:-<not found>}"
echo "  Gatling + k6 run  : ${latest_run:-<not found>}"
echo "  Dev page bake-in  : make perf-bake"

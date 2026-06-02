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

# Shared timestamp so JMH bench JSON, Gatling cross-backend run, and
# k6 surface output all land under one perf-reports/<TS>/ tree. Both
# sub-scripts honor PERF_TS when set.
export PERF_TS="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="perf-reports/$PERF_TS"
mkdir -p "$RUN_DIR"

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
log "Layer 2 — JMH microbenchmarks (no stack required)"
# Bench writes to perf-reports/bench-<own-ts>.json. After it runs we
# move the newest bench file under the shared run dir so the suite
# output stays self-contained.
make bench
latest_bench="$(ls -t perf-reports/bench-*.json 2>/dev/null | head -1 || true)"
if [[ -n "$latest_bench" ]]; then
  mv "$latest_bench" "$RUN_DIR/bench.json"
  log "bench JSON → $RUN_DIR/bench.json"
fi

# ── Layer 1 — Gatling cross-backend ────────────────────────────────────
log "Layer 1 — Gatling cross-backend (BACKENDS=${BACKENDS:-default} MODE=${MODE:-Game} OBS=$OBS)"
OBS="$OBS" make perf

# ── Layer 1b — k6 surfaces ────────────────────────────────────────────
# perf-run.sh tears the stack down on completion, so bring postgres +
# opening (for Kafka) back up. Each k6 surface is backend-agnostic for
# the gateway-mediated bits but the kafka surface needs Kafka up;
# bundling `opening` is the smallest profile that activates it.
log "Layer 1b — k6 surfaces (postgres + opening — kafka up for the kafka surface)"
EXTRA_PROFILES="opening"
if [[ "$OBS" == "true" ]]; then
  EXTRA_PROFILES="opening,obs"
fi
make stack-postgres EXTRA="$EXTRA_PROFILES"
wait_for_url "$GATEWAY_URL/api/stack-info" 120
# All three surfaces in one driver call so they land under the same
# perf-reports/<TS>/k6/{browser,grpc,kafka}/ tree.
SURFACES=browser,grpc,kafka make k6

# ── Summary ────────────────────────────────────────────────────────────
log "─── suite complete ──────────────────────────────────────"
echo "  Run dir          : $RUN_DIR"
echo "  JMH bench JSON   : $RUN_DIR/bench.json"
echo "  Gatling reports  : $RUN_DIR/<backend>/gatling/index.html"
echo "  Gatling summary  : $RUN_DIR/comparison.md"
echo "  k6 surfaces      : $RUN_DIR/k6/<surface>/summary.json"
echo "  Dev page bake-in : make perf-bake"

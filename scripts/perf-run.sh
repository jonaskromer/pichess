#!/usr/bin/env bash
#
# perf-run.sh — backend-comparison harness for the piChess performance suite.
#
# Usage:
#   scripts/perf-run.sh                              # all backends, smoke mode
#   BACKENDS=postgres,redis MODE=Stress scripts/perf-run.sh
#   BACKENDS=postgres OBS=true MODE=Endurance scripts/perf-run.sh
#
# Env vars (all optional):
#   BACKENDS   Comma-separated subset of {inmemory,postgres,mongo,redis,cassandra}.
#              Default: inmemory,postgres,mongo,redis,cassandra
#   MODE       Gatling simulation class name suffix. One of:
#              Game | Lobby | Stress | Endurance | Spike | Volume | Mixed
#              Default: Game
#   OBS        true|false — also bring up the prometheus+grafana profile so
#              the harness can snapshot metrics during the run. Default: false.
#   WARMUP_ITERS   Number of game-replay warm-up cycles before each Gatling
#                  run.  Default: 50.
#   PEAK_USERS, RAMP_SECONDS, HOLD_SECONDS, RATE_PER_SEC — overrides for
#              the Gatling SharedConfig system properties.
#
# Output:
#   perf-reports/<UTC-timestamp>/<backend>/
#     ├── gatling/             — full Gatling HTML report tree
#     ├── summary.txt          — extracted RPS, p50, p95, p99, error-rate
#     ├── prometheus-baseline.json   (when OBS=true)
#     └── prometheus-final.json      (when OBS=true)
#   perf-reports/<UTC-timestamp>/comparison.md
#                               cross-backend summary table

set -euo pipefail

# ───────────────────────────── config ────────────────────────────────────

BACKENDS="${BACKENDS:-inmemory,postgres,mongo,redis,cassandra}"
MODE="${MODE:-Game}"
OBS="${OBS:-false}"
WARMUP_ITERS="${WARMUP_ITERS:-50}"
PEAK_USERS="${PEAK_USERS:-50}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
HOLD_SECONDS="${HOLD_SECONDS:-60}"
RATE_PER_SEC="${RATE_PER_SEC:-5}"

# Resolve repo root from this script's location so the harness can be
# invoked from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# Honor a parent-provided timestamp (set by scripts/perf-all.sh) so the
# full suite lands under one perf-reports/<ts>/ tree. Standalone invocations
# fall back to a fresh stamp.
TS="${PERF_TS:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="perf-reports/$TS"
mkdir -p "$RUN_DIR"

# Snapshot active PICHESS_OPT_* selectors so the report can show which
# optimisation pairs were on which side during this run. See
# `Optimisation` in the `optimisation/` module.
env | grep -E '^PICHESS_OPT_' | sort > "$RUN_DIR/selectors.env" 2>/dev/null || true

GATEWAY_URL="http://localhost:8090"
LOBBY_URL="http://localhost:8092"
PROM_URL="http://localhost:9090"

# ──────────────────────── helper functions ──────────────────────────────

log() { printf '\n[\033[1;34mperf\033[0m %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

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

# Warm the active stack with a handful of full game replays so the JVMs
# JIT-compile the hot path before measurement begins. Errors are silenced
# because the cassandra stack in particular has a long settle window even
# after healthcheck passes.
warmup() {
  log "warming up ($WARMUP_ITERS iterations)"
  local i=0
  while ((i < WARMUP_ITERS)); do
    local session="warmup-$i-$RANDOM"
    # Create a fresh game and capture its id. The id is in the JSON
    # response from POST /api/games — extract with python so we don't
    # depend on jq being installed on the dev rig.
    local resp
    resp="$(curl -sf -X POST \
      -H "X-Session-Id: $session" \
      -H 'content-type: application/json' \
      -d '{}' "$GATEWAY_URL/api/games" 2>/dev/null || true)"
    if [[ -z "$resp" ]]; then ((i++)); continue; fi
    local gid
    gid="$(printf '%s' "$resp" | python3 -c \
      'import sys,json;print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"
    if [[ -z "$gid" ]]; then ((i++)); continue; fi
    for mv in "e2 e4" "e7 e5" "g1 f3" "b8 c6" "f1 b5" "a7 a6" "b5 a4" "g8 f6"; do
      curl -sf -X POST \
        -H "X-Session-Id: $session" \
        -H 'content-type: application/json' \
        -d "{\"move\":\"$mv\"}" \
        "$GATEWAY_URL/api/games/$gid/move" >/dev/null 2>&1 || true
    done
    ((i++))
  done
}

snapshot_prometheus() {
  local out="$1"
  if [[ "$OBS" == "true" ]]; then
    log "snapshotting Prometheus → $out"
    # Pull every series Prometheus currently knows about. For long runs
    # this is large but a one-shot snapshot is cheap.
    curl -sf "$PROM_URL/api/v1/query?query=%7B__name__%3D~%22.%2B%22%7D" \
      -o "$out" || echo '{}' > "$out"
  fi
}

run_gatling() {
  local out_dir="$1"
  log "running Gatling ${MODE}Simulation"
  sbt -batch \
    "-DpichessGatewayUrl=$GATEWAY_URL" \
    "-DpichessLobbyUrl=$LOBBY_URL" \
    "-DpichessPeakUsers=$PEAK_USERS" \
    "-DpichessRampSeconds=$RAMP_SECONDS" \
    "-DpichessHoldSeconds=$HOLD_SECONDS" \
    "-DpichessRatePerSec=$RATE_PER_SEC" \
    "gatling/Gatling/testOnly chess.gatling.${MODE}Simulation" \
    2>&1 | tee "$out_dir/sbt.log"
  # Pick the latest gatling/target/gatling/<runId>/ dir and copy.
  local latest
  latest="$(ls -dt gatling/target/gatling/*/ 2>/dev/null | head -1)"
  if [[ -z "$latest" ]]; then
    echo "no Gatling run found under gatling/target/gatling/" >&2
    return 1
  fi
  mkdir -p "$out_dir/gatling"
  cp -R "$latest"* "$out_dir/gatling/"
}

# Extract Gatling's simulation.log to a single-line summary. simulation.log
# has space-separated rows with REQUEST/USER markers; we want the global
# response-time percentiles + RPS + error rate. The HTML report has these
# pre-rendered; parse out of `index.html` since simulation.log requires
# the Gatling jar to interpret.
extract_summary() {
  local backend="$1" out_dir="$2"
  local summary="$out_dir/summary.txt"
  local idx="$out_dir/gatling/index.html"

  printf 'backend=%s\ncache=%s\nmode=%s\ntimestamp=%s\n' \
    "$backend" "${PICHESS_CACHE:-none}" "$MODE" "$TS" > "$summary"

  if [[ -f "$idx" ]]; then
    # Gatling 3.x writes statistics into js/stats.js. The file is JS
    # source — `var stats = { ... };` followed by a fillStats(...) helper
    # function — not parseable as JSON. The global "All Requests" wrapper
    # is the first object in the file, with each metric as
    # `"<name>": { "total": "<num>", ... }`. Targeted regex per metric
    # picks the first (global) match, which is what we want.
    local stats="$out_dir/gatling/js/stats.js"
    if [[ -f "$stats" ]]; then
      python3 - "$stats" "$summary" <<'PY' || true
import re, sys
stats_path, summary_path = sys.argv[1], sys.argv[2]
text = open(stats_path).read()

def pick(field, sub="total"):
    # Match the first occurrence — Gatling's outermost group ("All
    # Requests") is written before any per-request breakdowns.
    pat = re.compile(
        r'"' + re.escape(field) + r'"\s*:\s*\{[^}]*?"' +
        re.escape(sub) + r'"\s*:\s*"?([^",}\s]+)"?',
        re.S,
    )
    m = pat.search(text)
    return m.group(1) if m else "?"

with open(summary_path, "a") as f:
    f.write(f"requests_total={pick('numberOfRequests', 'total')}\n")
    f.write(f"requests_ok={pick('numberOfRequests', 'ok')}\n")
    f.write(f"requests_ko={pick('numberOfRequests', 'ko')}\n")
    f.write(f"mean_response_ms={pick('meanResponseTime', 'total')}\n")
    f.write(f"p50_ms={pick('percentiles1', 'total')}\n")
    f.write(f"p75_ms={pick('percentiles2', 'total')}\n")
    f.write(f"p95_ms={pick('percentiles3', 'total')}\n")
    f.write(f"p99_ms={pick('percentiles4', 'total')}\n")
    f.write(f"mean_rps={pick('meanNumberOfRequestsPerSecond', 'total')}\n")
PY
    fi
  fi
  cat "$summary"
}

# ─────────────────────────── main loop ──────────────────────────────────

log "perf run starting → $RUN_DIR"
log "backends=$BACKENDS mode=$MODE obs=$OBS cache=${PICHESS_CACHE:-none}"

# Build the EXTRA= list passed to `make stack-<bk>`. `obs` activates
# Prometheus/Grafana/Jaeger; `redis` brings up the redis container so
# the persistence layer's CachedGameRepository decorator can use it.
# We deliberately don't activate the redis profile when the *primary*
# backend is already redis (no double-redis caching).
build_extras() {
  local backend="$1"
  local parts=()
  if [[ "$OBS" == "true" ]]; then
    parts+=("obs")
  fi
  if [[ -n "${PICHESS_CACHE:-}" && "$PICHESS_CACHE" != "none" && \
        "$PICHESS_CACHE" != "$backend" ]]; then
    parts+=("$PICHESS_CACHE")
  fi
  if (( ${#parts[@]} == 0 )); then
    echo ""
  else
    local IFS=,
    echo "EXTRA=${parts[*]}"
  fi
}

for backend in ${BACKENDS//,/ }; do
  log "── backend: $backend cache: ${PICHESS_CACHE:-none} ──"
  out_dir="$RUN_DIR/$backend"
  mkdir -p "$out_dir"

  extra_flag="$(build_extras "$backend")"

  # PICHESS_CACHE is read by docker-compose at `up -d` time (game-service
  # + repository both consume it via the persistence layer's BackendConfig).
  PICHESS_CACHE="${PICHESS_CACHE:-none}" make "stack-$backend" $extra_flag

  # Wait for gateway + lobby healthcheck. Gateway has no /healthcheck yet
  # but does serve `/api/stack-info`; that's good enough as a readiness probe.
  wait_for_url "$GATEWAY_URL/api/stack-info" 120
  wait_for_url "$LOBBY_URL/healthcheck" 60 || true

  warmup
  snapshot_prometheus "$out_dir/prometheus-baseline.json"
  run_gatling "$out_dir"
  snapshot_prometheus "$out_dir/prometheus-final.json"
  extract_summary "$backend" "$out_dir"
done

log "tearing stack down"
make stack-down || true

# Cross-backend comparison.
"$SCRIPT_DIR/perf-summary.sh" "$RUN_DIR"
log "done → $RUN_DIR/comparison.md"

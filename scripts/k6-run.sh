#!/usr/bin/env bash
#
# k6-run.sh — driver for Layer 1b of the piChess perf stack.
#
# Sibling of scripts/perf-run.sh: rather than rotating backends and
# running Gatling per backend, this drives the k6 container against the
# *currently active* stack. Backend rotation, if needed, is a future
# extension of perf-run.sh (set K6=true on that script to fold in).
#
# Usage:
#   scripts/k6-run.sh                                       # SURFACES=browser
#   SURFACES=browser,kafka scripts/k6-run.sh
#   PICHESS_K6_VUS=10 PICHESS_K6_DURATION=60s scripts/k6-run.sh
#
# Env vars (all optional):
#   SURFACES             Comma-separated subset of {browser,kafka,grpc}.
#                        Default: browser
#   PICHESS_K6_VUS       Virtual users per surface. Default: 5.
#   PICHESS_K6_DURATION  Per-surface max duration. Default: 30s.
#   K6_GATEWAY_URL       Default: http://localhost:8090
#   K6_LOBBY_URL         Default: http://localhost:8092
#   K6_KAFKA_BROKERS     Default: localhost:29092  (PLAINTEXT_HOST listener)
#   K6_GRPC_TARGET       Default: localhost:9000   (game-service gRPC)
#
# Note: PICHESS_K6_VUS / PICHESS_K6_DURATION are NOT k6's reserved
# K6_VUS / K6_DURATION — those override the script's scenarios block
# and would silently disable the browser type wiring.
#
# Output:
#   perf-reports/<UTC-ts>/k6/<surface>/
#     ├── summary.json     — k6 --summary-export
#     └── stdout.log       — full run log
#
# A thresholds breach exits the surface's container non-zero; the
# driver continues to the next surface and exits non-zero at the end if
# *any* surface failed (so CI sees a single pass/fail).

set -euo pipefail

SURFACES="${SURFACES:-browser}"
PICHESS_K6_VUS="${PICHESS_K6_VUS:-5}"
PICHESS_K6_DURATION="${PICHESS_K6_DURATION:-30s}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# Honor a parent-provided timestamp (set by scripts/perf-all.sh) so the
# full suite lands under one perf-reports/<ts>/ tree. Standalone
# invocations fall back to a fresh stamp.
TS="${PERF_TS:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="perf-reports/$TS/k6"
mkdir -p "$RUN_DIR"

log() { printf '\n[\033[1;34mk6\033[0m %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# Map each surface to its script path inside the container. The compose
# service bind-mounts ./k6/scripts → /scripts, so the paths below are
# what k6 sees, not host paths.
surface_script() {
  case "$1" in
    browser)        echo "/scripts/browser/lobby-flow.js" ;;
    kafka)          echo "/scripts/kafka/game-events.js" ;;
    kafka-complete) echo "/scripts/kafka/complete-games.js" ;;
    grpc)           echo "/scripts/grpc/game-service.js" ;;
    grpc-analyze)   echo "/scripts/grpc/analyze.js" ;;
    *)              echo "" ;;
  esac
}

run_surface() {
  local surface="$1"
  local script
  script="$(surface_script "$surface")"
  if [[ -z "$script" ]]; then
    log "unknown surface: $surface — skipping"
    return 0
  fi

  local host_script="${script#/scripts/}"
  if [[ ! -f "k6/scripts/$host_script" ]]; then
    log "no script at k6/scripts/$host_script — surface '$surface' not implemented yet, skipping"
    return 0
  fi

  local out_dir="$RUN_DIR/$surface"
  mkdir -p "$out_dir"
  log "running surface '$surface' → $out_dir"

  # The k6 script's handleSummary writes /out/<surface>/summary.json.
  # We point /out at perf-reports/<ts>/k6/ so each surface lands in its
  # own subdir without extra orchestration.
  PICHESS_K6_VUS="$PICHESS_K6_VUS" \
  PICHESS_K6_DURATION="$PICHESS_K6_DURATION" \
  docker compose --profile k6 run --rm \
    -v "$ROOT_DIR/perf-reports/$TS/k6:/out" \
    k6 run "$script" \
    2>&1 | tee "$out_dir/stdout.log"
}

failed=0
for s in ${SURFACES//,/ }; do
  if ! run_surface "$s"; then
    failed=1
    log "surface '$s' failed (threshold breach or error)"
  fi
done

log "k6 run complete → $RUN_DIR"
exit $failed

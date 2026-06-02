#!/usr/bin/env bash
#
# db-matrix.sh — runs the perf suite across the full backend × cache ×
# workload grid for the persistence experiment. See docs/perf-experiments.md
# for context.
#
# Two modes:
#
#   Default (lite mode) — app services stay warm across rotations; only
#     the DB container and the three backend-dependent services
#     (game-service, repository, lobby-service) restart per backend.
#     Faster + lower-memory: ~3-4 GB peak vs heavy mode's ~6-8 GB.
#
#   MATRIX_HEAVY=true — original behaviour. Full `docker compose down`
#     followed by `make stack-<backend>` per rotation. Slower and
#     heavier but with cleanest per-rotation state.
#
# Per tuple (backend, cache, workload), in either mode:
#   1. Make sure the right backend container is running and the three
#      backend-dependent services have been (re)started with the
#      matching PICHESS_BACKEND + PICHESS_CACHE env.
#   2. Wait for gateway + lobby readiness, plus Prometheus first-scrape
#      if OBS=true.
#   3. Warm the JVMs with game replays.
#   4. Snapshot Prometheus (if OBS=true), run Gatling, snapshot again.
#   5. Extract the per-run summary.
#
# When all tuples finish:
#   - matrix.md and matrix-summary.csv land in perf-reports/<TS>/matrix/
#   - The active stack is torn down (also on Ctrl-C, via the EXIT trap).
#
# Usage:
#   scripts/db-matrix.sh                                       # lite, OBS=true
#   OBS=false scripts/db-matrix.sh                              # lite, no obs
#   MATRIX_HEAVY=true scripts/db-matrix.sh                      # heavy mode
#   BACKENDS=postgres,mongo WORKLOADS=Game scripts/db-matrix.sh
#
# Env vars (all optional):
#   BACKENDS       Comma-separated subset of {inmemory,postgres,mongo,redis,cassandra}.
#                  Default: all five.
#   WORKLOADS      Comma-separated Gatling simulation suffixes. Default: Game,Stress.
#   WARMUP_ITERS   Per-config warmup game-replay count. Default: 20.
#   PEAK_USERS, RAMP_SECONDS, HOLD_SECONDS, RATE_PER_SEC — Gatling knobs.
#   OBS            true|false — bring up Prometheus/Grafana/Jaeger and
#                  capture before/after snapshots. Default: true.
#   MATRIX_HEAVY   true|false — see modes above. Default: false.
#

set -euo pipefail

# ───────────────────────────── config ────────────────────────────────────

BACKENDS="${BACKENDS:-inmemory,postgres,mongo,redis,cassandra}"
WORKLOADS="${WORKLOADS:-Game,Stress}"
WARMUP_ITERS="${WARMUP_ITERS:-20}"
PEAK_USERS="${PEAK_USERS:-50}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
HOLD_SECONDS="${HOLD_SECONDS:-60}"
RATE_PER_SEC="${RATE_PER_SEC:-5}"
OBS="${OBS:-true}"
MATRIX_HEAVY="${MATRIX_HEAVY:-false}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="perf-reports/$TS"
MATRIX_DIR="$RUN_DIR/matrix"
mkdir -p "$MATRIX_DIR"

# Capture every PICHESS_OPT_* env var visible at run start so the
# generated report can show the active selector state alongside the
# numbers. Without this, env changes between `make db-matrix` and
# `make perf-report` would silently desync the metadata.
env | grep -E '^PICHESS_OPT_' | sort > "$RUN_DIR/selectors.env" 2>/dev/null || true

GATEWAY_URL="http://localhost:8090"
LOBBY_URL="http://localhost:8092"
PROM_URL="http://localhost:9090"

# Services that read PICHESS_BACKEND / PICHESS_CACHE at startup and
# therefore need to be recreated when those change. Gateway is excluded
# — it talks to game-service over gRPC and doesn't touch persistence
# directly.
BACKEND_DEPS="game-service repository lobby-service"

# ──────────────────────── helper functions ──────────────────────────────

log() { printf '\n[\033[1;36mdb-matrix\033[0m %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

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

# Wait until Prometheus has scraped a delta-tracking metric (`jvm_gc_collection_seconds_sum`)
# from at least 3 services. JVM trackers tick every 10s on top of the
# 5s scrape interval, so the first useful baseline isn't available for
# ~15s after services pass their healthcheck. Without this wait,
# baseline snapshots land before the trackers fire and every GC/CPU
# delta in the report ends up n/a. Only called when OBS=true.
wait_for_prometheus() {
  local tries="${1:-30}"
  while ((tries > 0)); do
    local n
    n="$(curl -sf "$PROM_URL/api/v1/query?query=jvm_gc_collection_seconds_sum" 2>/dev/null \
          | python3 -c \
          'import json,sys
try:
  d=json.load(sys.stdin)
  rs=d.get("data",{}).get("result",[])
  print(len(rs))
except Exception:
  print(0)' 2>/dev/null || echo 0)"
    if [[ "$n" =~ ^[0-9]+$ ]] && (( n >= 4 )); then
      return 0
    fi
    sleep 2
    ((tries--))
  done
  log "warning: <4 services emitted JVM metrics; some baseline deltas may be n/a"
  return 0
}

# End-to-end probe: POST /api/games and require a 200 with a `gameId`
# in the body. Exercises gateway → gRPC → game-service → persistence,
# so a non-200 means *something* in that chain isn't ready yet
# (typically game-service still booting after a force-recreate).
wait_for_game_service_grpc() {
  local tries="${1:-60}"
  while ((tries > 0)); do
    local resp
    resp="$(curl -sf -X POST \
      -H 'X-Session-Id: probe' \
      -H 'content-type: application/json' \
      -d '{}' "$GATEWAY_URL/api/games" 2>/dev/null || true)"
    if [[ -n "$resp" ]] && \
       printf '%s' "$resp" | python3 -c \
         'import sys,json
try:
  d = json.load(sys.stdin)
  sys.exit(0 if d.get("id") else 1)
except Exception:
  sys.exit(1)' 2>/dev/null
    then
      return 0
    fi
    sleep 1
    ((tries--))
  done
  log "warning: gateway → gRPC → game-service probe never returned a gameId"
  return 0
}

# Poll docker for a container's health status. Some services don't
# define a healthcheck — fall back to a short grace sleep in that case.
wait_for_container_healthy() {
  local name="$1" tries="${2:-60}"
  while ((tries > 0)); do
    local status
    status="$(docker inspect --format '{{.State.Health.Status}}' "pichess-$name" 2>/dev/null || echo missing)"
    case "$status" in
      healthy) return 0 ;;
      missing) return 1 ;;
      "<no value>"|none|unknown)
        # Container exists but has no healthcheck. Wait briefly then trust it.
        sleep 2
        return 0
        ;;
    esac
    sleep 1
    ((tries--))
  done
  log "warning: $name never became healthy within timeout"
  return 0
}

warmup() {
  log "warming up ($WARMUP_ITERS iterations)"
  local i=0
  while ((i < WARMUP_ITERS)); do
    local session="warmup-$i-$RANDOM"
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
  if [[ "$OBS" != "true" ]]; then
    # Write an empty snapshot so the report's lookup gracefully sees nothing.
    echo '{"status":"success","data":{"resultType":"vector","result":[]}}' > "$out"
    return
  fi
  log "snapshotting Prometheus → $out"
  curl -sf "$PROM_URL/api/v1/query?query=%7B__name__%3D~%22.%2B%22%7D" \
    -o "$out" || echo '{}' > "$out"
}

run_gatling() {
  local mode="$1" out_dir="$2"
  log "running Gatling ${mode}Simulation"
  sbt -batch \
    "-DpichessGatewayUrl=$GATEWAY_URL" \
    "-DpichessLobbyUrl=$LOBBY_URL" \
    "-DpichessPeakUsers=$PEAK_USERS" \
    "-DpichessRampSeconds=$RAMP_SECONDS" \
    "-DpichessHoldSeconds=$HOLD_SECONDS" \
    "-DpichessRatePerSec=$RATE_PER_SEC" \
    "gatling/Gatling/testOnly chess.gatling.${mode}Simulation" \
    2>&1 | tee "$out_dir/sbt.log"
  local latest
  latest="$(ls -dt gatling/target/gatling/*/ 2>/dev/null | head -1)"
  if [[ -z "$latest" ]]; then
    echo "no Gatling run found under gatling/target/gatling/" >&2
    return 1
  fi
  mkdir -p "$out_dir/gatling"
  cp -R "$latest"* "$out_dir/gatling/"
}

extract_summary() {
  local backend="$1" cache="$2" mode="$3" out_dir="$4"
  local summary="$out_dir/summary.txt"
  local idx="$out_dir/gatling/index.html"

  printf 'backend=%s\ncache=%s\nmode=%s\ntimestamp=%s\n' \
    "$backend" "$cache" "$mode" "$TS" > "$summary"

  if [[ -f "$idx" ]]; then
    local stats="$out_dir/gatling/js/stats.js"
    if [[ -f "$stats" ]]; then
      python3 - "$stats" "$summary" <<'PY' || true
import re, sys
stats_path, summary_path = sys.argv[1], sys.argv[2]
text = open(stats_path).read()

def pick(field, sub="total"):
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

caches_for_backend() {
  case "$1" in
    inmemory|redis) echo "none" ;;
    *)              echo "none redis" ;;
  esac
}

# ──────────────────────── lite-mode plumbing ────────────────────────────

# Bring up gateway + the three backend-dependent services with the
# inmemory backend, plus optional `obs`. Called once at the start of
# a lite-mode run; subsequent rotations only restart what's necessary.
bring_up_initial_stack() {
  log "── bring up initial stack (inmemory, OBS=$OBS) ──"
  local profiles=()
  [[ "$OBS" == "true" ]] && profiles+=("--profile" "obs")

  PICHESS_BACKEND=inmemory PICHESS_CACHE=none PICHESS_KAFKA="" \
    TRACING_ENABLED=true \
    docker compose "${profiles[@]}" up -d gateway game-service repository lobby-service \
    $([[ "$OBS" == "true" ]] && echo "prometheus grafana jaeger")

  wait_for_url "$GATEWAY_URL/api/stack-info" 180
  wait_for_url "$LOBBY_URL/healthcheck" 60 || true
  wait_for_game_service_grpc
  [[ "$OBS" == "true" ]] && wait_for_prometheus
}

# Track the active backend+cache so we can no-op swaps that don't
# change anything. Initialised after bring_up_initial_stack.
current_backend=""
current_cache=""

swap_to_backend() {
  local new_backend="$1"
  local new_cache="$2"

  if [[ "$new_backend" == "$current_backend" && "$new_cache" == "$current_cache" ]]; then
    return 0
  fi

  log "── swap: $current_backend+$current_cache → $new_backend+$new_cache ──"

  # Resolve the docker-compose service name for the chosen backend.
  # `mongo` maps to `mongodb`; everything else is identity.
  resolve_db_svc() {
    case "$1" in
      mongo) echo "mongodb" ;;
      *)     echo "$1" ;;
    esac
  }

  # 1. Remove the old DB container outright (not just stop). The
  # persistence layer's schema migration isn't idempotent — `CREATE
  # TYPE games` etc. fails on second run against the same data dir
  # with "duplicate key violates pg_type_typname_nsp_index". Easiest
  # to start each rotation with a fresh data dir.
  case "$current_backend" in
    postgres|mongo|redis|cassandra)
      local old_db_svc
      old_db_svc="$(resolve_db_svc "$current_backend")"
      docker compose rm -fsv "$old_db_svc" >/dev/null 2>&1 || true
      ;;
  esac

  # If the cache changed away from redis and the new backend isn't
  # redis, drop the redis cache container too — it's stateful and we
  # want a clean slate.
  if [[ "$current_cache" == "redis" && "$new_cache" != "redis" \
        && "$new_backend" != "redis" ]]; then
    docker compose rm -fsv redis >/dev/null 2>&1 || true
  fi

  # 2. Bring up the new DB container fresh.
  case "$new_backend" in
    postgres|mongo|redis|cassandra)
      local db_svc
      db_svc="$(resolve_db_svc "$new_backend")"
      docker compose --profile "$new_backend" up -d --no-deps "$db_svc"
      wait_for_container_healthy "$db_svc"
      ;;
  esac

  # 3. Bring up the redis cache container fresh if needed (only when
  # the cache is redis AND the primary backend isn't redis). Always
  # rm + up to match the "fresh per rotation" model above.
  if [[ "$new_cache" == "redis" && "$new_backend" != "redis" ]]; then
    docker compose rm -fsv redis >/dev/null 2>&1 || true
    docker compose --profile redis up -d --no-deps redis
    wait_for_container_healthy redis
  fi

  # 4. Recreate the backend-dependent services in two phases. Starting
  # all three simultaneously triggers a Postgres catalog race —
  # game-service + lobby-service both run `PostgresSchema.ensure()`
  # concurrently, and two `CREATE TABLE IF NOT EXISTS games` against
  # the same fresh DB collide inside pg_type with "duplicate key
  # violates pg_type_typname_nsp_index". Bringing game-service up
  # first, waiting for its migration to commit, then bringing the
  # other two up means they hit a populated schema and `IF NOT EXISTS`
  # is a no-op.
  local common_env=(
    "-e" "PICHESS_BACKEND=$new_backend"
    "-e" "PICHESS_CACHE=$new_cache"
    "-e" "PICHESS_KAFKA="
    "-e" "TRACING_ENABLED=true"
  )
  PICHESS_BACKEND="$new_backend" PICHESS_CACHE="$new_cache" PICHESS_KAFKA="" \
    TRACING_ENABLED=true \
    docker compose up -d --no-deps --force-recreate game-service

  # 5. Wait for the gateway → gRPC → game-service → DB chain to be
  # live. A successful POST guarantees game-service ran and committed
  # the schema migration. Only then is it safe to bring up the other
  # services that also touch the schema.
  wait_for_url "$GATEWAY_URL/api/stack-info" 90
  wait_for_game_service_grpc

  # 6. Now repository + lobby-service can safely run their startup
  # paths against the already-populated schema.
  PICHESS_BACKEND="$new_backend" PICHESS_CACHE="$new_cache" PICHESS_KAFKA="" \
    TRACING_ENABLED=true \
    docker compose up -d --no-deps --force-recreate repository lobby-service

  wait_for_url "$LOBBY_URL/healthcheck" 60 || true
  # JVMs are still warm from the previous rotation; the warmup() call
  # in the main loop re-JITs the new backend's hot paths.

  current_backend="$new_backend"
  current_cache="$new_cache"
}

# ──────────────────────── heavy-mode plumbing ───────────────────────────

# The original per-rotation full-stack down/up. Kept for callers who
# want guaranteed-clean state at the cost of runtime + memory.
build_extras_heavy() {
  local backend="$1" cache="$2"
  local parts=()
  [[ "$OBS" == "true" ]] && parts+=("obs")
  if [[ -n "$cache" && "$cache" != "none" && "$cache" != "$backend" ]]; then
    parts+=("$cache")
  fi
  if (( ${#parts[@]} == 0 )); then echo ""; else local IFS=,; echo "EXTRA=${parts[*]}"; fi
}

# ──────────────────────── pre-flight ────────────────────────────────────

cleanup() {
  log "tearing stack down"
  # Stop every profile so nothing's left running from either mode.
  docker compose --profile postgres --profile mongo --profile cassandra \
                 --profile redis --profile opening --profile analytics \
                 --profile tui --profile obs --profile k6 down \
                 >/dev/null 2>&1 || true
  # Clear the .pichess-stack state file so `make stack-status` doesn't lie.
  rm -f .pichess-stack 2>/dev/null || true
}
trap cleanup EXIT

total_configs=0
for backend in ${BACKENDS//,/ }; do
  for cache in $(caches_for_backend "$backend"); do
    for workload in ${WORKLOADS//,/ }; do
      total_configs=$((total_configs + 1))
    done
  done
done

log "── db matrix start — $total_configs configs → $MATRIX_DIR ──"
log "mode=$([[ "$MATRIX_HEAVY" == "true" ]] && echo heavy || echo lite) backends=$BACKENDS workloads=$WORKLOADS obs=$OBS"

# ──────────────────────── main loop ─────────────────────────────────────

done_configs=0

if [[ "$MATRIX_HEAVY" != "true" ]]; then
  bring_up_initial_stack
  current_backend="inmemory"
  current_cache="none"
fi

for backend in ${BACKENDS//,/ }; do
  for cache in $(caches_for_backend "$backend"); do
    for workload in ${WORKLOADS//,/ }; do
      done_configs=$((done_configs + 1))
      label="$backend+$cache"
      log "[$done_configs/$total_configs] $label / $workload"

      out_dir="$MATRIX_DIR/$label/$workload"
      mkdir -p "$out_dir"

      if [[ "$MATRIX_HEAVY" == "true" ]]; then
        # Original behaviour — full stack rotation per tuple.
        extra_flag="$(build_extras_heavy "$backend" "$cache")"
        TRACING_ENABLED=true PICHESS_CACHE="$cache" \
          make "stack-$backend" $extra_flag
        wait_for_url "$GATEWAY_URL/api/stack-info" 180
        wait_for_url "$LOBBY_URL/healthcheck" 60 || true
        wait_for_game_service_grpc
        [[ "$OBS" == "true" ]] && wait_for_prometheus
      else
        # Lite mode — swap only what's necessary.
        swap_to_backend "$backend" "$cache"
      fi

      warmup
      snapshot_prometheus "$out_dir/prometheus-baseline.json"
      if ! run_gatling "$workload" "$out_dir"; then
        log "Gatling failed for $label/$workload — moving on"
      fi
      snapshot_prometheus "$out_dir/prometheus-final.json"
      extract_summary "$backend" "$cache" "$workload" "$out_dir"
    done
  done
done

# ──────────────────────── aggregation ───────────────────────────────────

log "writing matrix.md + matrix-summary.csv"

python3 - "$MATRIX_DIR" <<'PY'
import os, sys, re, csv, json
matrix_dir = sys.argv[1]

rows = []
for config in sorted(os.listdir(matrix_dir)):
    cfg_path = os.path.join(matrix_dir, config)
    if not os.path.isdir(cfg_path):
        continue
    for workload in sorted(os.listdir(cfg_path)):
        wl_path = os.path.join(cfg_path, workload)
        summary = os.path.join(wl_path, "summary.txt")
        if not os.path.isfile(summary):
            continue
        kv = {}
        with open(summary) as f:
            for line in f:
                if "=" in line:
                    k, v = line.strip().split("=", 1)
                    kv[k] = v
        rows.append({
            "config":   config,
            "backend":  kv.get("backend", "?"),
            "cache":    kv.get("cache", "?"),
            "workload": kv.get("mode", "?"),
            "requests_ok":      kv.get("requests_ok", "?"),
            "requests_ko":      kv.get("requests_ko", "?"),
            "mean_rps":         kv.get("mean_rps", "?"),
            "p50_ms":           kv.get("p50_ms", "?"),
            "p95_ms":           kv.get("p95_ms", "?"),
            "p99_ms":           kv.get("p99_ms", "?"),
            "mean_response_ms": kv.get("mean_response_ms", "?"),
        })

csv_path = os.path.join(matrix_dir, "matrix-summary.csv")
with open(csv_path, "w", newline="") as f:
    if rows:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

md_lines = ["# DB matrix — " + os.path.basename(os.path.dirname(matrix_dir)), ""]

def num(s):
    try:    return float(s)
    except: return float("inf")

workloads = sorted({r["workload"] for r in rows})
for wl in workloads:
    md_lines.append(f"## Workload: {wl}")
    md_lines.append("")
    md_lines.append("| Rank | Backend | Cache | Requests OK | Requests KO | Mean RPS | p50 ms | p95 ms | p99 ms |")
    md_lines.append("|---:|---|---|---:|---:|---:|---:|---:|---:|")
    wl_rows = [r for r in rows if r["workload"] == wl]
    wl_rows.sort(key=lambda r: num(r["p95_ms"]))
    for i, r in enumerate(wl_rows, start=1):
        md_lines.append(
            f"| {i} | {r['backend']} | {r['cache']} | {r['requests_ok']} | "
            f"{r['requests_ko']} | {r['mean_rps']} | {r['p50_ms']} | "
            f"{r['p95_ms']} | {r['p99_ms']} |"
        )
    md_lines.append("")

md_path = os.path.join(matrix_dir, "matrix.md")
with open(md_path, "w") as f:
    f.write("\n".join(md_lines))

print(f"matrix.md → {md_path}")
print(f"matrix-summary.csv → {csv_path}")
PY

log "── db matrix complete → $MATRIX_DIR ──"
cat "$MATRIX_DIR/matrix.md"

#!/usr/bin/env bash
#
# perf-features.sh — drive the NEW feature simulations (complete-game/archive/
# analyze/analytics/spectate/tournament) against an ALREADY-RUNNING stack.
#
# Unlike perf-run.sh this does NOT rotate backends — the DB choice is locked
# (mongo primary + redis cache), so bring that stack up once and point this at
# it. Use it for the bottleneck hunt over the previously-uncovered surfaces.
#
# Prereqs (bring up yourself, e.g. `make stack-mongo EXTRA=analytics OBS=true`):
#   gateway :8090, repository :8091, lobby :8092, analytics :8093, game-service
#   gRPC :9000, plus Kafka + repository + analytics + spark for the event tail.
#   For the tournament sim: a tournament server reachable at $TOURNAMENT_URL with
#   the gateway's PICHESS_TOURNAMENT_URL pointed at it, seeded via
#   scripts/tournament-seed.sh (this script will seed if SEED_TOURNAMENT=1).
#
# Env (all optional): SIMS (space list, default all), PEAK_USERS, RAMP_SECONDS,
#   HOLD_SECONDS, RATE_PER_SEC, USERS, ANALYZE_DEPTH, TOURNAMENT_URL,
#   SEED_TOURNAMENT=1, plus the standard service URLs.
#
# Output: perf-reports/<ts>/features/<sim>/gatling/ + sbt.log per sim.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8090}"
LOBBY_URL="${LOBBY_URL:-http://localhost:8092}"
REPOSITORY_URL="${REPOSITORY_URL:-http://localhost:8091}"
ANALYTICS_URL="${ANALYTICS_URL:-http://localhost:8093}"
TOURNAMENT_URL="${TOURNAMENT_URL:-http://localhost:8086}"

USERS="${USERS:-10}"
PEAK_USERS="${PEAK_USERS:-50}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
HOLD_SECONDS="${HOLD_SECONDS:-60}"
RATE_PER_SEC="${RATE_PER_SEC:-5}"
ANALYZE_DEPTH="${ANALYZE_DEPTH:-6}"

# Default: every feature sim except the tournament one (which needs a seeded
# server). The tournament sim is appended below iff a seed is available.
SIMS="${SIMS:-CompleteGame Archive Analyze AnalyticsQuery SpectateIndex}"

TS="${PERF_TS:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="perf-reports/$TS/features"
mkdir -p "$RUN_DIR"

log() { printf '\n[\033[1;35mperf-features\033[0m %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# Common SharedConfig props every sim accepts.
COMMON_PROPS=(
  "-DpichessGatewayUrl=$GATEWAY_URL"
  "-DpichessLobbyUrl=$LOBBY_URL"
  "-DpichessRepositoryUrl=$REPOSITORY_URL"
  "-DpichessAnalyticsUrl=$ANALYTICS_URL"
  "-DpichessUsers=$USERS"
  "-DpichessPeakUsers=$PEAK_USERS"
  "-DpichessRampSeconds=$RAMP_SECONDS"
  "-DpichessHoldSeconds=$HOLD_SECONDS"
  "-DpichessRatePerSec=$RATE_PER_SEC"
  "-DpichessAnalyzeDepth=$ANALYZE_DEPTH"
)

# Optionally stand up + seed a tournament, then add the tournament sim.
TOURNAMENT_PROPS=()
if [[ "${SEED_TOURNAMENT:-0}" == "1" ]]; then
  log "seeding tournament on $TOURNAMENT_URL"
  TOURNAMENT_URL="$TOURNAMENT_URL" scripts/tournament-seed.sh || log "seed failed — skipping tournament sim"
fi
SEED_FILE="${SEED_OUT:-/tmp/pichess-tournament-seed.env}"
if [[ -f "$SEED_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$SEED_FILE"
  if [[ -n "${PICHESS_TOURNAMENT_ID:-}" && -n "${PICHESS_SPECTATE_GAME_IDS:-}" ]]; then
    TOURNAMENT_PROPS=(
      "-DpichessTournamentId=$PICHESS_TOURNAMENT_ID"
      "-DpichessSpectateGameIds=$PICHESS_SPECTATE_GAME_IDS"
    )
    SIMS="$SIMS TournamentSpectate"
    log "tournament sim enabled: $PICHESS_TOURNAMENT_ID ($PICHESS_SPECTATE_GAME_IDS)"
  fi
fi

run_sim() {
  local sim="$1"; shift
  local out_dir="$RUN_DIR/$sim"
  mkdir -p "$out_dir"
  log "running ${sim}Simulation"
  sbt -batch "${COMMON_PROPS[@]}" "$@" \
    "gatling/Gatling/testOnly chess.gatling.${sim}Simulation" \
    2>&1 | tee "$out_dir/sbt.log" || log "${sim}Simulation returned non-zero (assertions?) — continuing"
  local latest
  latest="$(ls -dt gatling/target/gatling/*/ 2>/dev/null | head -1)"
  if [[ -n "$latest" ]]; then
    mkdir -p "$out_dir/gatling"
    cp -R "$latest"* "$out_dir/gatling/" 2>/dev/null || true
  fi
}

for sim in $SIMS; do
  case "$sim" in
    TournamentSpectate) run_sim "$sim" "${TOURNAMENT_PROPS[@]}" ;;
    *)                  run_sim "$sim" ;;
  esac
done

log "feature perf reports under $RUN_DIR"

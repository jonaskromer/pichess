#!/usr/bin/env bash
# Build and (re)start one service or the full stack via docker compose.
#
# Usage:
#   ./scripts/dev-up.sh                     # build + start everything
#   ./scripts/dev-up.sh all                 # same
#   ./scripts/dev-up.sh gateway             # rebuild + restart only gateway
#   ./scripts/dev-up.sh game-service        # rebuild + restart only game-service
#   ./scripts/dev-up.sh repository          # rebuild + restart only repository
#   ./scripts/dev-up.sh lobby-service       # rebuild + restart only lobby-service
#   ./scripts/dev-up.sh opening-service     # rebuild + restart only opening-service
#   ./scripts/dev-up.sh analytics-service   # rebuild + restart only analytics-service
#
# `all` mode lazily builds only images that are missing from the local
# Docker daemon — first run rebuilds everything, subsequent runs are
# no-ops on the SBT side. Use single-service mode (`./scripts/dev-up.sh
# gateway`) when you want to force a rebuild after editing one service;
# that path always re-publishes the chosen image and restarts only that
# container with --no-deps, so the DBs and other services keep running.
set -euo pipefail

target="${1:-all}"

# Map of image name -> sbt task that builds it. Kept in sync with build.sbt
# `dockerBuildAll` alias and the Docker plugin packageName settings.
declare -a IMAGES=(
  "pichess-game-service:latest|gameService/Docker/publishLocal"
  "pichess-repository:latest|repository/Docker/publishLocal"
  "pichess-lobby-service:latest|lobbyService/Docker/publishLocal"
  "pichess-opening-service:latest|openingService/Docker/publishLocal"
  "pichess-analytics-service:latest|analyticsService/Docker/publishLocal"
  "pichess-gateway:latest|gateway/Docker/publishLocal"
)

# Print every sbt task whose image isn't present locally.
missing_sbt_tasks() {
  for entry in "${IMAGES[@]}"; do
    local image="${entry%%|*}"
    local task="${entry##*|}"
    if ! docker image inspect "$image" >/dev/null 2>&1; then
      printf '%s\n' "$task"
    fi
  done
}

build_missing() {
  local tasks
  tasks=$(missing_sbt_tasks)
  if [[ -z "$tasks" ]]; then
    return 0
  fi
  # Join newline-separated tasks with `; ` for sbt's batch syntax.
  local joined
  joined=$(printf '%s' "$tasks" | paste -sd';' -)
  echo "Building missing images: $joined"
  sbt "$joined"
}

case "$target" in
  all)
    build_missing
    docker compose up -d
    ;;
  gateway)
    sbt gateway/Docker/publishLocal
    docker compose up -d --no-deps gateway
    ;;
  game-service)
    sbt gameService/Docker/publishLocal
    docker compose up -d --no-deps game-service
    ;;
  repository)
    sbt repository/Docker/publishLocal
    docker compose up -d --no-deps repository
    ;;
  lobby-service)
    sbt lobbyService/Docker/publishLocal
    docker compose up -d --no-deps lobby-service
    ;;
  opening-service)
    sbt openingService/Docker/publishLocal
    docker compose up -d --no-deps opening-service
    ;;
  analytics-service)
    sbt analyticsService/Docker/publishLocal
    docker compose up -d --no-deps analytics-service
    ;;
  *)
    echo "unknown service: $target (expected: all | gateway | game-service | repository | lobby-service | opening-service | analytics-service)" >&2
    exit 1
    ;;
esac

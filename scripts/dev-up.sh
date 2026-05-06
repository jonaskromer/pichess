#!/usr/bin/env bash
# Build and (re)start one service or the full stack via docker compose.
#
# Usage:
#   ./scripts/dev-up.sh                  # build + start everything
#   ./scripts/dev-up.sh all              # same
#   ./scripts/dev-up.sh gateway          # rebuild + restart only gateway
#   ./scripts/dev-up.sh game-service     # rebuild + restart only game-service
#   ./scripts/dev-up.sh repository       # rebuild + restart only repository
#
# Single-service mode uses --no-deps so kafka and the other services keep
# running. Layered Docker images mean only the changed module's jar layer
# is rebuilt — wall-clock should be under ~20s for a one-file edit.
set -euo pipefail

target="${1:-all}"

case "$target" in
  all)
    sbt 'gameService/Docker/publishLocal; gateway/Docker/publishLocal; repository/Docker/publishLocal'
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
  *)
    echo "unknown service: $target (expected: all | gateway | game-service | repository)" >&2
    exit 1
    ;;
esac

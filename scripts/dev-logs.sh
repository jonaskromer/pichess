#!/usr/bin/env bash
# Tail logs for one or more services (or everything if none specified).
#
# Usage:
#   ./scripts/dev-logs.sh                  # tail everything
#   ./scripts/dev-logs.sh gateway          # only gateway
#   ./scripts/dev-logs.sh game-service kafka
set -euo pipefail
docker compose logs -f --tail=100 "$@"

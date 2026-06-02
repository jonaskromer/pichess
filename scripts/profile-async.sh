#!/usr/bin/env bash
#
# profile-async.sh — attach async-profiler to a running service container
# for `DURATION` seconds and copy out the flame graph.
#
# Usage:
#   scripts/profile-async.sh SERVICE [DURATION] [EVENT] [OUT]
#     SERVICE   docker-compose service name (gateway, game-service, ...)
#     DURATION  seconds. Default 60.
#     EVENT     cpu | alloc | lock | wall. Default cpu.
#     OUT       output file path on the host. Default
#               perf-reports/profiles/async-<event>-<service>-<ts>.html
#
# Prereqs:
#   - The target container must have asprof on PATH. The `obs` profile's
#     Dockerfile.profile overlay images bake it in. Alternatively, this
#     script will fall back to `docker cp`-ing a local asprof binary
#     into the container at /tmp/asprof if one is found at
#     $ASPROF_BIN (e.g. /opt/homebrew/bin/asprof).
#
# Output is a flame-graph HTML file, openable in any browser.

set -euo pipefail

SERVICE="${1:-}"
DURATION="${2:-60}"
EVENT="${3:-cpu}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
DEFAULT_OUT="perf-reports/profiles/async-${EVENT}-${SERVICE}-${TS}.html"
OUT="${4:-$DEFAULT_OUT}"

if [[ -z "$SERVICE" ]]; then
  echo "usage: $0 SERVICE [DURATION] [EVENT] [OUT]" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

# Detect whether asprof is already on PATH in the container. Service
# entrypoints from sbt-native-packager pin PID 1 to the JVM, so we always
# target PID 1.
if docker compose exec -T "$SERVICE" sh -c 'command -v asprof >/dev/null 2>&1'; then
  echo "[profile-async] asprof present in $SERVICE; profiling for ${DURATION}s"
else
  if [[ -z "${ASPROF_BIN:-}" || ! -x "$ASPROF_BIN" ]]; then
    cat >&2 <<EOF
[profile-async] asprof not found in container '$SERVICE', and no host
\$ASPROF_BIN is set. Either:
  (a) Bake asprof into the service image via docker/Dockerfile.profile, or
  (b) brew install async-profiler and re-run with ASPROF_BIN=\$(which asprof).
EOF
    exit 1
  fi
  echo "[profile-async] copying $ASPROF_BIN into $SERVICE:/tmp/asprof"
  docker compose cp "$ASPROF_BIN" "$SERVICE:/tmp/asprof"
  docker compose exec -T "$SERVICE" chmod +x /tmp/asprof
fi

CONTAINER_OUT="/tmp/profile-${EVENT}-${TS}.html"
ASPROF_CMD="${ASPROF_CMD:-asprof}"
docker compose exec -T "$SERVICE" sh -c "
  if command -v asprof >/dev/null 2>&1; then
    asprof -d $DURATION -e $EVENT -f $CONTAINER_OUT 1
  else
    /tmp/asprof -d $DURATION -e $EVENT -f $CONTAINER_OUT 1
  fi
"

docker compose cp "$SERVICE:$CONTAINER_OUT" "$OUT"
echo "[profile-async] flame graph → $OUT"

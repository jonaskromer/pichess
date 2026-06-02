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
#     into the container at /tmp/asprof if one is found at $ASPROF_BIN
#     (e.g. /opt/homebrew/bin/asprof). When falling back, the script
#     also copies `libasyncProfiler.so` from the same directory as
#     $ASPROF_BIN's parent dir's `lib/` — asprof loads the lib
#     dynamically and won't start without it.
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
  # asprof loads libasyncProfiler.so via dlopen, and its search is
  # *relative to the bin's parent* — `<root>/bin/asprof` looks for
  # `<root>/lib/libasyncProfiler.so`. LD_LIBRARY_PATH isn't enough
  # (asprof 4.x ignores it for the bundled lib). So we recreate the
  # distribution layout under /tmp/asprof-dist/ inside the container.
  ASPROF_LIB="$(dirname "$(dirname "$ASPROF_BIN")")/lib/libasyncProfiler.so"
  if [[ ! -f "$ASPROF_LIB" ]]; then
    cat >&2 <<EOF
[profile-async] expected libasyncProfiler.so at $ASPROF_LIB but didn't find it.
Set ASPROF_LIB explicitly or fix the distribution layout.
EOF
    exit 1
  fi
  echo "[profile-async] copying $ASPROF_BIN + libasyncProfiler.so into $SERVICE:/tmp/asprof-dist/"
  docker compose exec -T "$SERVICE" mkdir -p /tmp/asprof-dist/bin /tmp/asprof-dist/lib
  docker compose cp "$ASPROF_BIN" "$SERVICE:/tmp/asprof-dist/bin/asprof"
  docker compose cp "$ASPROF_LIB" "$SERVICE:/tmp/asprof-dist/lib/libasyncProfiler.so"
  # `docker compose cp` preserves the host-side +x bit, so chmod is a
  # belt-and-braces fallback — and most service images run as non-root
  # which means the chmod itself will EPERM. Failure here is fine.
  docker compose exec -T "$SERVICE" chmod +x /tmp/asprof-dist/bin/asprof 2>/dev/null || true
fi

CONTAINER_OUT="/tmp/profile-${EVENT}-${TS}.html"
# kernel.perf_event_paranoid is 2 on Docker Desktop on macOS, which
# blocks the default `cpu` event (`perf_events`). Map `cpu` to `wall`
# transparently so a default invocation works without forcing the
# user to know the host-OS detail. Pass `cpu` literally if the user
# really wants perf_events.
PROFILE_EVENT="$EVENT"
if [[ "$EVENT" == "cpu" ]]; then
  PROFILE_EVENT="wall"
fi
docker compose exec -T "$SERVICE" sh -c "
  if command -v asprof >/dev/null 2>&1; then
    asprof -d $DURATION -e $PROFILE_EVENT -o flamegraph -f $CONTAINER_OUT 1
  else
    /tmp/asprof-dist/bin/asprof -d $DURATION -e $PROFILE_EVENT -o flamegraph -f $CONTAINER_OUT 1
  fi
"

docker compose cp "$SERVICE:$CONTAINER_OUT" "$OUT"
echo "[profile-async] flame graph → $OUT"

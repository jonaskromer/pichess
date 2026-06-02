#!/usr/bin/env bash
#
# perf-summary.sh — walks every summary.txt under a perf-reports run dir
# and emits a markdown comparison table. Called automatically at the end
# of perf-run.sh; can also be run by hand against an existing dump:
#
#   scripts/perf-summary.sh perf-reports/20260602T100000Z

set -euo pipefail

RUN_DIR="${1:-}"
if [[ -z "$RUN_DIR" || ! -d "$RUN_DIR" ]]; then
  echo "usage: $0 <perf-reports/<ts> directory>" >&2
  exit 1
fi

OUT="$RUN_DIR/comparison.md"

{
  echo "# Performance comparison — $(basename "$RUN_DIR")"
  echo
  echo "| Backend | Mode | Requests OK | Requests KO | Mean RPS | p50 ms | p95 ms | p99 ms |"
  echo "|---|---|---:|---:|---:|---:|---:|---:|"

  for summary in "$RUN_DIR"/*/summary.txt; do
    [[ -f "$summary" ]] || continue
    # shellcheck disable=SC1090
    source <(grep -E '^(backend|mode|requests_ok|requests_ko|mean_rps|p50_ms|p95_ms|p99_ms)=' "$summary")
    printf '| %s | %s | %s | %s | %s | %s | %s | %s |\n' \
      "${backend:-?}" "${mode:-?}" "${requests_ok:-?}" "${requests_ko:-?}" \
      "${mean_rps:-?}" "${p50_ms:-?}" "${p95_ms:-?}" "${p99_ms:-?}"
  done
} > "$OUT"

cat "$OUT"

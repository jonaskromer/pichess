#!/usr/bin/env bash
# Generate one shields "endpoint" JSON per metric badge into a directory.
#
# Each file fully defines its own badge (label / message / color), so the
# README just points an `endpoint` badge at it — no query params. The files are
# published as assets on the mutable `metrics` pre-release by
# .github/workflows/metrics.yml; nothing is committed and no third-party
# analysis service is involved. Pure git + bash — no external counter to install.
#
# Usage: bash scripts/gen-badges.sh [output-dir]   (default: badges)
set -eu

cd "$(git rev-parse --show-toplevel)"
outdir="${1:-badges}"
mkdir -p "$outdir"

# badge <file> <label> <message> <color>  — write a shields endpoint badge.
badge() {
  printf '{ "schemaVersion": 1, "label": "%s", "message": "%s", "color": "%s" }\n' \
    "$2" "$3" "$4" >"$outdir/$1"
}

# --- lines of Scala across tracked sources (rounded to the nearest 1k) -------
loc_raw=$(git ls-files -z '*.scala' | xargs -0 cat 2>/dev/null | wc -l | tr -d ' ')
if [ "$loc_raw" -ge 1000 ]; then loc="$(((loc_raw + 500) / 1000))k"; else loc="$loc_raw"; fi
badge loc.json "lines of code" "$loc" "blueviolet"

# --- sbt subprojects (project/crossProject), minus the aggregate root -------
modules=$(grep -E '^lazy val [A-Za-z0-9_]+ = (project|crossProject)' build.sbt \
  | grep -vc '^lazy val root ' || true)
badge modules.json "modules" "$modules" "blue"

# --- tech-debt markers in Scala sources (green at 0, orange otherwise) -------
techdebt=$(git grep -IE '\b(TODO|FIXME|HACK|XXX)\b' -- '*.scala' | wc -l | tr -d ' ')
if [ "$techdebt" -eq 0 ]; then td_color="brightgreen"; else td_color="orange"; fi
badge techdebt.json "tech debt" "$techdebt" "$td_color"

echo "wrote to $outdir/:"
for f in "$outdir"/*.json; do printf '  %s -> %s' "$f" "$(cat "$f")"; done

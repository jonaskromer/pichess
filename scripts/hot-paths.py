#!/usr/bin/env python3
"""Rank operations by call count + per-root-request multiplier.

Reads traces from a running Jaeger (default http://localhost:16686),
canonicalises gameId UUIDs in operation names, and prints:
  1. Top-N operations by total call count across the sample.
  2. Per-root-operation multipliers: how many child spans of each kind
     fire per root request. Multipliers > 1.0 mark reducible-call
     candidates — e.g. before we fixed the duplicate-save bug, every
     `POST /api/games/<id>/move` produced 2.0× `db.game-repo.save`.

Run a perf test first so the traces are populated, then call this.

Usage:
    python3 scripts/hot-paths.py
    python3 scripts/hot-paths.py --service game-service  # restrict to one service
    python3 scripts/hot-paths.py --lookback 30m --limit 2000
    python3 scripts/hot-paths.py --top 30
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass

JAEGER_DEFAULT = "http://localhost:16686"

_UUID_RE = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b",
    re.IGNORECASE,
)


def canonicalise(op: str) -> str:
    """Replace UUID gameIds inside operation names with `<id>` so all
    requests against different games collapse into one bucket."""
    return _UUID_RE.sub("<id>", op)


@dataclass(frozen=True)
class SpanKey:
    service: str
    op: str  # canonical


def fetch_services(jaeger: str) -> list[str]:
    with urllib.request.urlopen(f"{jaeger}/api/services") as r:
        return list(json.load(r).get("data") or [])


def fetch_traces(jaeger: str, service: str, lookback: str, limit: int) -> list[dict]:
    q = urllib.parse.urlencode({
        "service": service,
        "lookback": lookback,
        "limit": str(limit),
    })
    try:
        with urllib.request.urlopen(f"{jaeger}/api/traces?{q}") as r:
            return list(json.load(r).get("data") or [])
    except Exception as e:
        print(f"  ! failed to fetch traces for {service}: {e}", file=sys.stderr)
        return []


def collect(
    traces: list[dict],
) -> tuple[Counter[SpanKey], dict[SpanKey, Counter[SpanKey]], Counter[SpanKey]]:
    """Returns:
       - total_counts: spans per (service, canonical op)
       - per_root: nested counter — for each root span op, how many
         child spans of each kind appear in its trace
       - root_counts: how many traces each root op produced (the
         denominator for the multiplier)
    """
    totals: Counter[SpanKey] = Counter()
    per_root: dict[SpanKey, Counter[SpanKey]] = defaultdict(Counter)
    root_counts: Counter[SpanKey] = Counter()

    for trace in traces:
        procs = trace.get("processes", {}) or {}
        spans = trace.get("spans", []) or []
        root_span = None
        for s in spans:
            svc = procs.get(s.get("processID", ""), {}).get("serviceName", "?")
            key = SpanKey(svc, canonicalise(s.get("operationName", "")))
            totals[key] += 1
            if not s.get("references"):
                # Multiple roots can exist if the trace bridges services
                # without parent-child propagation. Keep the first as
                # the trace root for multiplier purposes.
                if root_span is None:
                    root_span = key
        if root_span is not None:
            root_counts[root_span] += 1
            for s in spans:
                svc = procs.get(s.get("processID", ""), {}).get("serviceName", "?")
                key = SpanKey(svc, canonicalise(s.get("operationName", "")))
                if key != root_span:
                    per_root[root_span][key] += 1
    return totals, per_root, root_counts


def print_totals(totals: Counter[SpanKey], top: int) -> None:
    print(f"\n── Top operations by call count (top {top}) ──")
    print(f"  {'rank':>4}  {'count':>8}  {'service':<14}  operation")
    print(f"  {'-'*4:>4}  {'-'*8:>8}  {'-'*14:<14}  {'-'*40}")
    for i, (key, n) in enumerate(totals.most_common(top), 1):
        print(f"  {i:>4}  {n:>8}  {key.service:<14}  {key.op}")


def print_multipliers(
    per_root: dict[SpanKey, Counter[SpanKey]],
    root_counts: Counter[SpanKey],
    top: int,
) -> None:
    print(
        f"\n── Per-root-request span multipliers "
        f"(child-spans / root-call; reducible if multi > 1.0 unnecessarily) ──"
    )
    print(
        f"  {'root operation':<46}  {'child operation':<46}  "
        f"{'n root':>6}  {'count':>6}  {'multi':>5}"
    )
    print(f"  {'-'*46}  {'-'*46}  {'-'*6}  {'-'*6}  {'-'*5}")
    # For each root op (ranked by frequency), show its top child ops.
    for root_key, n_root in root_counts.most_common(top):
        children = per_root.get(root_key, Counter())
        if not children:
            continue
        for child_key, n_child in children.most_common(8):
            multi = n_child / n_root if n_root else 0.0
            print(
                f"  {f'{root_key.service}: {root_key.op}'[:46]:<46}  "
                f"{f'{child_key.service}: {child_key.op}'[:46]:<46}  "
                f"{n_root:>6}  {n_child:>6}  {multi:>5.2f}"
            )


def main() -> int:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("--jaeger", default=JAEGER_DEFAULT, help="Jaeger UI base URL")
    p.add_argument("--service", default=None, help="restrict to one service")
    p.add_argument("--lookback", default="10m", help="Jaeger lookback window (e.g. 5m, 1h)")
    p.add_argument("--limit", type=int, default=1500, help="max traces per service to fetch")
    p.add_argument("--top", type=int, default=20, help="number of operations to print")
    args = p.parse_args()

    services = [args.service] if args.service else fetch_services(args.jaeger)
    services = [s for s in services if s and s != "jaeger-all-in-one"]
    if not services:
        print("No services found in Jaeger.", file=sys.stderr)
        return 1

    traces: list[dict] = []
    seen_ids: set[str] = set()
    for svc in services:
        for t in fetch_traces(args.jaeger, svc, args.lookback, args.limit):
            tid = t.get("traceID")
            if tid and tid not in seen_ids:
                seen_ids.add(tid)
                traces.append(t)
    print(f"Aggregating {len(traces)} unique traces from {len(services)} service(s).")

    totals, per_root, root_counts = collect(traces)
    print_totals(totals, args.top)
    print_multipliers(per_root, root_counts, args.top)
    return 0


if __name__ == "__main__":
    sys.exit(main())

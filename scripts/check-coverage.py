#!/usr/bin/env python3
"""Parse scoverage XML and report lines missing coverage.

Usage:
    python3 scripts/check-coverage.py

Generate the report first with:
    sbt coverage test coverageReport
"""

import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def find_report(project_root: Path) -> Path:
    candidates = sorted(project_root.glob("target/scala-*/scoverage-report/scoverage.xml"))
    if not candidates:
        print("No scoverage report found. Run: sbt coverage test coverageReport")
        sys.exit(1)
    return candidates[-1]


def main() -> int:
    project_root = Path(__file__).parent.parent
    report = find_report(project_root)

    root = ET.parse(report).getroot()
    stmt_rate   = root.get("statement-rate", "?")
    branch_rate = root.get("branch-rate", "?")

    # Collect uncovered lines grouped by relative source path
    uncovered: defaultdict[str, set[int]] = defaultdict(set)
    for stmt in root.iter("statement"):
        if stmt.get("invocation-count") == "0":
            source = stmt.get("source", "")
            line   = int(stmt.get("line", 0))
            try:
                rel = str(Path(source).relative_to(project_root))
            except ValueError:
                rel = source
            uncovered[rel].add(line)

    print(f"Statement coverage : {stmt_rate}%")
    print(f"Branch coverage    : {branch_rate}%")

    if not uncovered:
        print("\nAll lines covered.")
        return 0

    total_lines = sum(len(lines) for lines in uncovered.values())
    print(f"\nMissing coverage: {total_lines} line(s) across {len(uncovered)} file(s)\n")
    for filepath in sorted(uncovered):
        print(f"  {filepath}")
        for line in sorted(uncovered[filepath]):
            print(f"    line {line}")

    return 1


if __name__ == "__main__":
    sys.exit(main())

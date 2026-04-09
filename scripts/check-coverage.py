#!/usr/bin/env python3
"""Parse scoverage XML and report lines missing coverage.

Usage:
    python3 scripts/check-coverage.py            # one line per uncovered line
    python3 scripts/check-coverage.py -v         # show method/symbol per statement
    python3 scripts/check-coverage.py --filter Main.scala   # only files matching substring

Generate the report first with:
    sbt coverage test coverageReport
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import List


@dataclass
class Stmt:
    line: int
    method: str
    start: int          # absolute character offset into the source file
    end: int            # absolute character offset (exclusive)
    branch: bool        # whether scoverage classified this as a branch
    source_path: Path   # absolute path to the source file


def find_report(project_root: Path) -> Path:
    candidates = sorted(project_root.glob("target/scala-*/scoverage-report/scoverage.xml"))
    if not candidates:
        print("No scoverage report found. Run: sbt coverage test coverageReport")
        sys.exit(1)
    return candidates[-1]


def collect_uncovered(report: Path, project_root: Path) -> "defaultdict[str, List[Stmt]]":
    root = ET.parse(report).getroot()
    by_file: defaultdict[str, List[Stmt]] = defaultdict(list)
    for stmt in root.iter("statement"):
        if stmt.get("invocation-count") != "0":
            continue
        source = stmt.get("source", "")
        try:
            rel = str(Path(source).relative_to(project_root))
        except ValueError:
            rel = source
        by_file[rel].append(
            Stmt(
                line=int(stmt.get("line", 0)),
                method=stmt.get("method", ""),
                start=int(stmt.get("start", 0)),
                end=int(stmt.get("end", 0)),
                branch=stmt.get("branch", "false") == "true",
                source_path=Path(source),
            )
        )
    return by_file


# Cache file contents so we don't re-read for every statement.
_file_cache: dict[Path, str] = {}


def file_contents(path: Path) -> str:
    if path not in _file_cache:
        try:
            _file_cache[path] = path.read_text()
        except OSError:
            _file_cache[path] = ""
    return _file_cache[path]


def snippet_for(stmt: Stmt, max_len: int = 80) -> str:
    text = file_contents(stmt.source_path)
    if not text:
        return "<source unavailable>"
    raw = text[stmt.start : stmt.end]
    # Collapse whitespace so the snippet fits on a line.
    one_line = " ".join(raw.split())
    if len(one_line) > max_len:
        one_line = one_line[: max_len - 3] + "..."
    return one_line


def coverage_rates(report: Path) -> tuple[str, str]:
    root = ET.parse(report).getroot()
    return root.get("statement-rate", "?"), root.get("branch-rate", "?")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Show uncovered scoverage statements")
    p.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="show method/symbol/tree info for each uncovered statement",
    )
    p.add_argument(
        "--filter",
        default="",
        help="only show files whose path contains this substring",
    )
    return p.parse_args()


def main() -> int:
    args = parse_args()
    project_root = Path(__file__).parent.parent
    report = find_report(project_root)

    stmt_rate, branch_rate = coverage_rates(report)
    by_file = collect_uncovered(report, project_root)

    if args.filter:
        by_file = defaultdict(
            list,
            {f: stmts for f, stmts in by_file.items() if args.filter in f},
        )

    print(f"Statement coverage : {stmt_rate}%")
    print(f"Branch coverage    : {branch_rate}%")

    if not by_file:
        print("\nAll lines covered." if not args.filter else "\nNo uncovered statements match filter.")
        return 0

    total_stmts = sum(len(stmts) for stmts in by_file.values())
    total_lines = sum(len({s.line for s in stmts}) for stmts in by_file.values())
    print(
        f"\nMissing coverage: {total_stmts} statement(s) on {total_lines} line(s) "
        f"across {len(by_file)} file(s)\n"
    )

    for filepath in sorted(by_file):
        print(f"  {filepath}")
        stmts = sorted(by_file[filepath], key=lambda s: (s.line, s.start))
        if args.verbose:
            for s in stmts:
                tag = "branch" if s.branch else " stmt "
                method = s.method or "<top-level>"
                snippet = snippet_for(s)
                print(
                    f"    line {s.line:4}  [{tag}]  {method:30}  {snippet}"
                )
        else:
            # One line per uncovered source line; pick a representative snippet
            # (the first statement on that line) and list the enclosing methods.
            grouped: defaultdict[int, list[Stmt]] = defaultdict(list)
            for s in stmts:
                grouped[s.line].append(s)
            for line in sorted(grouped):
                line_stmts = grouped[line]
                methods = ", ".join(
                    sorted({s.method or "<top-level>" for s in line_stmts})
                )
                snippet = snippet_for(line_stmts[0])
                print(f"    line {line:4}  in {methods}")
                print(f"             ↳ {snippet}")

    return 1


if __name__ == "__main__":
    sys.exit(main())

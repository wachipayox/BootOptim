#!/usr/bin/env python3
"""Summarize independent phase-replay VM results."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = []
    fixture_ids = set()
    for path in sorted(args.results_dir.rglob("result.json")):
        row = json.loads(path.read_text(encoding="utf-8"))
        rows.append(row)
        fixture_ids.add(row.get("fixture_id"))
    if len(fixture_ids) > 1:
        raise SystemExit(f"results use different fixtures: {sorted(fixture_ids)}")
    lines = [
        "# Isolated phase replay",
        "",
        "These are deterministic graph-model results, not time-to-main-menu measurements.",
        "",
        "| Policy | Workers | Makespan units | Critical path units | Queue wait units | Speedup vs single |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in sorted(rows, key=lambda item: (item.get("policy", ""), item.get("workers", 0))):
        speedup = row.get("parallelism_speedup_vs_single")
        lines.append(
            f"| {row.get('policy', 'n/a')} | {row.get('workers', 'n/a')} | "
            f"{row.get('makespan_units', 'n/a')} | {row.get('critical_path_units', 'n/a')} | "
            f"{row.get('queue_wait_units', 'n/a')} | {speedup if speedup is not None else 'n/a'} |"
        )
    lines.extend(["", f"Fixture: `{next(iter(fixture_ids), 'n/a')}`", ""])
    args.output.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Analyze BootOptim's diagnostic FML construction trace without summing overlap."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class Node:
    mod: str
    deps: list[str] = field(default_factory=list)
    construct_begin: int | None = None
    construct_end: int | None = None
    subscriber_begin: int | None = None
    subscriber_end: int | None = None
    event_begin: int | None = None
    event_end: int | None = None

    @property
    def start(self) -> int | None:
        return self.construct_begin if self.construct_begin is not None else self.event_begin

    @property
    def end(self) -> int | None:
        return self.event_end if self.event_end is not None else self.construct_end

    def duration(self, begin: int | None, end: int | None) -> float | None:
        if begin is None or end is None or end < begin:
            return None
        return (end - begin) / 1_000_000.0

    @property
    def node_ms(self) -> float | None:
        return self.duration(self.start, self.end)

    @property
    def construct_ms(self) -> float | None:
        return self.duration(self.construct_begin, self.construct_end)

    @property
    def subscriber_ms(self) -> float | None:
        return self.duration(self.subscriber_begin, self.subscriber_end)

    @property
    def event_ms(self) -> float | None:
        return self.duration(self.event_begin, self.event_end)


def load_events(path: Path) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"invalid JSONL at line {line_number}: {exc}") from exc
        if not isinstance(event.get("ns"), int) or not isinstance(event.get("kind"), str):
            raise SystemExit(f"invalid event shape at line {line_number}")
        events.append(event)
    events.sort(key=lambda event: (event["ns"], event.get("seq", 0)))
    return events


def first_ns(events: list[dict[str, Any]], kind: str) -> int | None:
    for event in events:
        if event["kind"] == kind:
            return event["ns"]
    return None


def fmt(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}"


def analyze(events: list[dict[str, Any]]) -> dict[str, Any]:
    headers = [event for event in events if event["kind"] == "profile_header"]
    disabled = [event for event in events if event["kind"] == "profile_disabled"]
    if len(headers) != 1:
        raise SystemExit(f"expected exactly one profile_header, found {len(headers)}")
    if disabled:
        raise SystemExit(f"profile fail-closed: {disabled[-1].get('detail')}")

    gate_begin = first_ns(events, "gate_begin")
    gate_end = first_ns(events, "gate_end")
    if gate_begin is None or gate_end is None or gate_end < gate_begin:
        raise SystemExit("missing or invalid construction gate boundaries")

    nodes: dict[str, Node] = {}
    for event in events:
        mod = event.get("mod")
        kind = event["kind"]
        if not mod:
            continue
        node = nodes.setdefault(mod, Node(mod=mod))
        ns = event["ns"]
        if kind == "dependencies":
            detail = event.get("detail") or ""
            node.deps = [value for value in detail.split(",") if value]
        elif kind == "construct_begin":
            node.construct_begin = ns
        elif kind == "construct_end":
            node.construct_end = ns
        elif kind == "subscriber_begin":
            node.subscriber_begin = ns
        elif kind == "subscriber_end":
            node.subscriber_end = ns
        elif kind == "construct_event_begin":
            node.event_begin = ns
        elif kind == "construct_event_end":
            node.event_end = ns

    complete = {mod: node for mod, node in nodes.items() if node.start is not None and node.end is not None}
    if not complete:
        raise SystemExit("no complete construction nodes found")

    sink = max(complete.values(), key=lambda node: node.end or -1)
    chain_reverse: list[Node] = []
    seen: set[str] = set()
    cursor = sink
    while True:
        if cursor.mod in seen:
            raise SystemExit(f"dependency cycle while reconstructing observed chain at {cursor.mod}")
        seen.add(cursor.mod)
        chain_reverse.append(cursor)
        predecessors = [complete[dep] for dep in cursor.deps if dep in complete and complete[dep].end is not None]
        if not predecessors:
            break
        cursor = max(predecessors, key=lambda node: node.end or -1)
    chain = list(reversed(chain_reverse))

    chain_rows = []
    for node in chain:
        dep_ends = [complete[dep].end for dep in node.deps if dep in complete and complete[dep].end is not None]
        ready_ns = max([gate_begin, *dep_ends])
        start = node.start or ready_ns
        queue_ms = max(0, start - ready_ns) / 1_000_000.0
        chain_rows.append(
            {
                "mod": node.mod,
                "deps": node.deps,
                "node_ms": node.node_ms,
                "construct_ms": node.construct_ms,
                "subscriber_ms": node.subscriber_ms,
                "event_ms": node.event_ms,
                "ready_to_start_ms": queue_ms,
                "start_ms_from_gate": (start - gate_begin) / 1_000_000.0,
                "end_ms_from_gate": ((node.end or start) - gate_begin) / 1_000_000.0,
            }
        )

    top_nodes = sorted(
        (node for node in complete.values() if node.node_ms is not None),
        key=lambda node: node.node_ms or 0.0,
        reverse=True,
    )[:20]

    dependency_missing = sorted(
        {dep for node in complete.values() for dep in node.deps if dep not in complete}
    )
    gate_ms = (gate_end - gate_begin) / 1_000_000.0
    sink_end = sink.end or gate_end
    observed_chain_span_ms = (sink_end - gate_begin) / 1_000_000.0
    tail_residual_ms = max(0, gate_end - sink_end) / 1_000_000.0
    chain_mod_set = {node.mod for node in chain}

    material = max(chain, key=lambda node: node.node_ms or 0.0)
    return {
        "profile_header": headers[0].get("detail"),
        "gate_ms": gate_ms,
        "complete_nodes": len(complete),
        "dependency_records": sum(1 for event in events if event["kind"] == "dependencies"),
        "critical_sink": sink.mod,
        "observed_dependency_chain_span_ms": observed_chain_span_ms,
        "gate_tail_residual_ms": tail_residual_ms,
        "critical_chain": chain_rows,
        "critical_chain_mods": [node.mod for node in chain],
        "material_critical_mod": material.mod,
        "material_critical_mod_node_ms": material.node_ms,
        "missing_dependency_nodes": dependency_missing,
        "top_nodes": [
            {
                "mod": node.mod,
                "node_ms": node.node_ms,
                "construct_ms": node.construct_ms,
                "subscriber_ms": node.subscriber_ms,
                "event_ms": node.event_ms,
                "on_critical_chain": node.mod in chain_mod_set,
            }
            for node in top_nodes
        ],
    }


def markdown(result: dict[str, Any]) -> str:
    lines = [
        "# FML construction critical-chain profile",
        "",
        f"- version gate: `{result['profile_header']}`",
        f"- construction gate: **{result['gate_ms']:.3f} ms**",
        f"- complete observed nodes: **{result['complete_nodes']}**",
        f"- dependency records: **{result['dependency_records']}**",
        f"- last-finishing node: **`{result['critical_sink']}`**",
        f"- gate start -> last node end: **{result['observed_dependency_chain_span_ms']:.3f} ms**",
        f"- post-node gate residual: **{result['gate_tail_residual_ms']:.3f} ms**",
        "",
        "Durations below are individual node walls. They are intentionally not summed across parallel nodes.",
        "",
        "## Observed last-predecessor chain",
        "",
        "| mod | node ms | construct ms | subscriber ms | construct-event ms | ready→start ms |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in result["critical_chain"]:
        lines.append(
            f"| `{row['mod']}` | {fmt(row['node_ms'])} | {fmt(row['construct_ms'])} | "
            f"{fmt(row['subscriber_ms'])} | {fmt(row['event_ms'])} | {fmt(row['ready_to_start_ms'])} |"
        )
    lines.extend(
        [
            "",
            f"Largest individual node on that dependency chain: **`{result['material_critical_mod']}`** "
            f"at **{result['material_critical_mod_node_ms']:.3f} ms**. This is an investigation target, not a savings claim.",
            "",
            "## Longest individual construction nodes",
            "",
            "| mod | node ms | construct ms | subscriber ms | construct-event ms | critical chain |",
            "| --- | ---: | ---: | ---: | ---: | :---: |",
        ]
    )
    for row in result["top_nodes"]:
        lines.append(
            f"| `{row['mod']}` | {fmt(row['node_ms'])} | {fmt(row['construct_ms'])} | "
            f"{fmt(row['subscriber_ms'])} | {fmt(row['event_ms'])} | {'yes' if row['on_critical_chain'] else 'no'} |"
        )
    if result["missing_dependency_nodes"]:
        lines.extend(
            [
                "",
                "Missing predecessor timing nodes: "
                + ", ".join(f"`{mod}`" for mod in result["missing_dependency_nodes"]),
            ]
        )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--json-output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    args = parser.parse_args()

    result = analyze(load_events(args.input))
    args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.markdown_output.write_text(markdown(result), encoding="utf-8")
    print(markdown(result), end="")


if __name__ == "__main__":
    main()

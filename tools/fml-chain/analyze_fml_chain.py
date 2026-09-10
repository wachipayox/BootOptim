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
    thread_id: int | None = None
    thread_name: str | None = None

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
    def constructor_exclusive_ms(self) -> float | None:
        construct = self.construct_ms
        subscriber = self.subscriber_ms
        if construct is None:
            return None
        if subscriber is None:
            return construct
        return max(0.0, construct - subscriber)

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
    observed_threads: dict[str, set[int]] = {}
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
        elif kind in {
            "construct_begin", "construct_end", "subscriber_begin", "subscriber_end",
            "construct_event_begin", "construct_event_end",
        }:
            tid = event.get("tid")
            if not isinstance(tid, int) or tid <= 0:
                raise SystemExit(f"missing worker thread identity for {kind} mod={mod}")
            observed_threads.setdefault(mod, set()).add(tid)
            if node.thread_id is None and kind in {"construct_begin", "construct_event_begin"}:
                node.thread_id = tid
                node.thread_name = event.get("thread")
            if kind == "construct_begin":
                node.construct_begin = ns
            elif kind == "construct_end":
                node.construct_end = ns
            elif kind == "subscriber_begin":
                node.subscriber_begin = ns
            elif kind == "subscriber_end":
                node.subscriber_end = ns
            elif kind == "construct_event_begin":
                node.event_begin = ns
                if node.thread_id is None:
                    node.thread_id = tid
                    node.thread_name = event.get("thread")
            elif kind == "construct_event_end":
                node.event_end = ns

    for mod, tids in observed_threads.items():
        if len(tids) != 1:
            raise SystemExit(f"construction node crossed threads unexpectedly: mod={mod} tids={sorted(tids)}")

    complete = {mod: node for mod, node in nodes.items() if node.start is not None and node.end is not None}
    if not complete:
        raise SystemExit("no complete construction nodes found")
    missing_thread = sorted(mod for mod, node in complete.items() if node.thread_id is None)
    if missing_thread:
        raise SystemExit("complete nodes missing worker identity: " + ", ".join(missing_thread))

    dependency_missing = sorted({dep for node in complete.values() for dep in node.deps if dep not in complete})
    if dependency_missing:
        raise SystemExit("dependency timing nodes missing: " + ", ".join(dependency_missing))

    # The executor can delay an otherwise dependency-ready node behind unrelated work. Reconstruct
    # that observed resource ordering from the immutable thread id rather than the non-unique thread
    # name (the hosted FML executor names all workers `modloading-worker-0`).
    by_thread: dict[int, list[Node]] = {}
    for node in complete.values():
        by_thread.setdefault(node.thread_id or -1, []).append(node)
    worker_predecessor: dict[str, str] = {}
    for tid, thread_nodes in by_thread.items():
        thread_nodes.sort(key=lambda node: (node.start or 0, node.end or 0, node.mod))
        previous: Node | None = None
        for node in thread_nodes:
            if previous is not None:
                if (previous.end or 0) > (node.start or 0):
                    raise SystemExit(
                        f"overlapping construction nodes on worker tid={tid}: {previous.mod} -> {node.mod}"
                    )
                worker_predecessor[node.mod] = previous.mod
            previous = node

    dependency_predecessor: dict[str, str] = {}
    causal_predecessor: dict[str, str] = {}
    causal_kind: dict[str, str] = {}
    for mod, node in complete.items():
        dep_pred = max(node.deps, key=lambda dep: complete[dep].end or -1) if node.deps else None
        if dep_pred is not None:
            dependency_predecessor[mod] = dep_pred
        worker_pred = worker_predecessor.get(mod)
        candidates: list[tuple[int, str, str]] = []
        if dep_pred is not None:
            candidates.append((complete[dep_pred].end or -1, "dependency", dep_pred))
        if worker_pred is not None:
            candidates.append((complete[worker_pred].end or -1, "worker", worker_pred))
        if candidates:
            latest_end = max(value[0] for value in candidates)
            latest = [value for value in candidates if value[0] == latest_end]
            chosen = latest[0]
            causal_predecessor[mod] = chosen[2]
            kinds = sorted({value[1] for value in latest if value[2] == chosen[2]})
            causal_kind[mod] = "+".join(kinds)

    sink = max(complete.values(), key=lambda node: node.end or -1)

    def unwind(predecessors: dict[str, str]) -> list[Node]:
        reverse: list[Node] = []
        seen: set[str] = set()
        cursor = sink.mod
        while True:
            if cursor in seen:
                raise SystemExit(f"cycle while reconstructing observed chain at {cursor}")
            seen.add(cursor)
            reverse.append(complete[cursor])
            predecessor = predecessors.get(cursor)
            if predecessor is None:
                break
            cursor = predecessor
        return list(reversed(reverse))

    execution_chain = unwind(causal_predecessor)
    dependency_chain = unwind(dependency_predecessor)

    execution_rows = []
    for node in execution_chain:
        dep_ends = [complete[dep].end for dep in node.deps]
        dependency_ready_ns = max([gate_begin, *dep_ends])
        worker_pred = worker_predecessor.get(node.mod)
        worker_ready_ns = max(gate_begin, complete[worker_pred].end or gate_begin) if worker_pred else gate_begin
        predecessor = causal_predecessor.get(node.mod)
        causal_ready_ns = max(gate_begin, complete[predecessor].end or gate_begin) if predecessor else gate_begin
        start = node.start or causal_ready_ns
        execution_rows.append(
            {
                "mod": node.mod,
                "deps": node.deps,
                "thread_id": node.thread_id,
                "thread_name": node.thread_name,
                "causal_predecessor": predecessor,
                "causal_predecessor_kind": causal_kind.get(node.mod, "gate"),
                "worker_predecessor": worker_pred,
                "node_ms": node.node_ms,
                "construct_ms": node.construct_ms,
                "constructor_exclusive_ms": node.constructor_exclusive_ms,
                "subscriber_ms": node.subscriber_ms,
                "event_ms": node.event_ms,
                "dependency_ready_to_start_ms": max(0, start - dependency_ready_ns) / 1_000_000.0,
                "worker_ready_to_start_ms": max(0, start - worker_ready_ns) / 1_000_000.0,
                "causal_dispatch_gap_ms": max(0, start - causal_ready_ns) / 1_000_000.0,
                "start_ms_from_gate": (start - gate_begin) / 1_000_000.0,
                "end_ms_from_gate": ((node.end or start) - gate_begin) / 1_000_000.0,
            }
        )

    top_nodes = sorted(
        (node for node in complete.values() if node.node_ms is not None),
        key=lambda node: node.node_ms or 0.0,
        reverse=True,
    )[:20]

    gate_ms = (gate_end - gate_begin) / 1_000_000.0
    sink_end = sink.end or gate_end
    observed_chain_span_ms = (sink_end - gate_begin) / 1_000_000.0
    tail_residual_ms = max(0, gate_end - sink_end) / 1_000_000.0
    execution_mod_set = {node.mod for node in execution_chain}
    dependency_mod_set = {node.mod for node in dependency_chain}
    material = max(execution_chain, key=lambda node: node.node_ms or 0.0)

    return {
        "profile_header": headers[0].get("detail"),
        "gate_ms": gate_ms,
        "complete_nodes": len(complete),
        "dependency_records": sum(1 for event in events if event["kind"] == "dependencies"),
        "worker_count": len(by_thread),
        "critical_sink": sink.mod,
        "observed_execution_chain_span_ms": observed_chain_span_ms,
        "gate_tail_residual_ms": tail_residual_ms,
        "observed_execution_critical_chain": execution_rows,
        "observed_execution_critical_chain_mods": [node.mod for node in execution_chain],
        "dependency_last_predecessor_chain_mods": [node.mod for node in dependency_chain],
        "material_critical_mod": material.mod,
        "material_critical_mod_node_ms": material.node_ms,
        "top_nodes": [
            {
                "mod": node.mod,
                "thread_id": node.thread_id,
                "node_ms": node.node_ms,
                "construct_ms": node.construct_ms,
                "constructor_exclusive_ms": node.constructor_exclusive_ms,
                "subscriber_ms": node.subscriber_ms,
                "event_ms": node.event_ms,
                "on_execution_critical_chain": node.mod in execution_mod_set,
                "on_dependency_chain": node.mod in dependency_mod_set,
            }
            for node in top_nodes
        ],
    }


def markdown(result: dict[str, Any]) -> str:
    lines = [
        "# FML construction observed critical-path profile",
        "",
        f"- version gate: `{result['profile_header']}`",
        f"- construction gate: **{result['gate_ms']:.3f} ms**",
        f"- complete observed nodes: **{result['complete_nodes']}**",
        f"- dependency records: **{result['dependency_records']}**",
        f"- observed executor workers: **{result['worker_count']}**",
        f"- last-finishing node: **`{result['critical_sink']}`**",
        f"- gate start -> last node end: **{result['observed_execution_chain_span_ms']:.3f} ms**",
        f"- post-node gate residual: **{result['gate_tail_residual_ms']:.3f} ms**",
        "",
        "The primary chain follows whichever actually released each node last: its latest direct dependency or the previous task on the same worker. This captures observed executor serialization without summing overlapping nodes.",
        "",
        "## Observed execution critical chain",
        "",
        "| mod | blocker | tid | node ms | constructor excl. ms | subscriber ms | construct-event ms | dep-ready→start ms | causal gap ms |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in result["observed_execution_critical_chain"]:
        blocker = row["causal_predecessor"] or "gate"
        blocker = f"{row['causal_predecessor_kind']}:{blocker}"
        lines.append(
            f"| `{row['mod']}` | `{blocker}` | {row['thread_id']} | {fmt(row['node_ms'])} | "
            f"{fmt(row['constructor_exclusive_ms'])} | {fmt(row['subscriber_ms'])} | {fmt(row['event_ms'])} | "
            f"{fmt(row['dependency_ready_to_start_ms'])} | {fmt(row['causal_dispatch_gap_ms'])} |"
        )
    lines.extend(
        [
            "",
            "Dependency-only last-predecessor lineage: "
            + " -> ".join(f"`{mod}`" for mod in result["dependency_last_predecessor_chain_mods"]),
            "",
            f"Largest individual node on the observed execution chain: **`{result['material_critical_mod']}`** "
            f"at **{result['material_critical_mod_node_ms']:.3f} ms**. This is an investigation target, not a savings claim.",
            "",
            "## Longest individual construction nodes",
            "",
            "| mod | tid | node ms | constructor excl. ms | subscriber ms | construct-event ms | execution chain | dependency chain |",
            "| --- | ---: | ---: | ---: | ---: | ---: | :---: | :---: |",
        ]
    )
    for row in result["top_nodes"]:
        lines.append(
            f"| `{row['mod']}` | {row['thread_id']} | {fmt(row['node_ms'])} | "
            f"{fmt(row['constructor_exclusive_ms'])} | {fmt(row['subscriber_ms'])} | {fmt(row['event_ms'])} | "
            f"{'yes' if row['on_execution_critical_chain'] else 'no'} | {'yes' if row['on_dependency_chain'] else 'no'} |"
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

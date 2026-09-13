#!/usr/bin/env python3
"""Validate and summarize BootOptim structured boot traces.

Critical-path wall is the union of real intervals along a dependency chain, never
an addition of inclusive listener/task durations. CPU is reported separately as
the producer-supplied task-end sum and is never treated as wall-clock savings.
"""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

SCHEMA = "bootoptim.boottrace"
VERSION = 1
EVENT_TYPES = {
    "phase_begin", "phase_end", "task_begin", "task_end", "barrier_wait", "barrier_open",
    "commit_begin", "commit_end", "blocked_on", "mod_callback", "resource_open",
    "resource_parse", "fallback", "error",
}
PAIRS = {"phase_begin": "phase_end", "commit_begin": "commit_end", "barrier_wait": "barrier_open"}
REVERSE_PAIRS = {value: key for key, value in PAIRS.items()}


@dataclass(frozen=True)
class Span:
    task_id: int
    start_ns: int
    end_ns: int
    phase: str | None
    parent_task_id: int | None
    dependency_ids: tuple[int, ...]
    cpu_ns: int | None

    @property
    def wall_ns(self) -> int:
        return max(0, self.end_ns - self.start_ns)


def load_trace(path: Path, allow_loss: bool = False) -> tuple[dict, list[dict], dict]:
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(records) < 2 or records[0].get("record") != "trace_header":
        raise ValueError("trace must begin with trace_header")
    if records[-1].get("record") != "trace_summary":
        raise ValueError("trace must end with trace_summary")
    header, summary = records[0], records[-1]
    events = [record for record in records[1:-1] if record.get("record") == "event"]
    if len(events) != len(records) - 2:
        raise ValueError("unknown record between header and summary")

    if header.get("schema") != SCHEMA or header.get("schema_version") != VERSION:
        raise ValueError("unsupported boot trace schema")
    if header.get("clock_kind") != "monotonic" or header.get("clock_source") != "System.nanoTime" or header.get("clock_origin") != "trace_init":
        raise ValueError("trace monotonic clock origin is missing or ambiguous")
    for name in ("jvm_start_epoch_ms", "trace_origin_epoch_ms", "trace_origin_mono_ns"):
        if not isinstance(header.get(name), int):
            raise ValueError(f"missing integer {name}")
    if header["trace_origin_epoch_ms"] < header["jvm_start_epoch_ms"]:
        raise ValueError("trace wall origin precedes JVM start")
    jvm_id = header.get("jvm_id")
    if not jvm_id or summary.get("jvm_id") != jvm_id:
        raise ValueError("header/summary JVM identity mismatch")
    if not isinstance(header.get("pid"), int):
        raise ValueError("missing JVM pid")

    previous_seq = -1
    sequence_gaps = 0
    for event in events:
        if event.get("v") != VERSION or event.get("jvm_id") != jvm_id:
            raise ValueError("event schema/JVM identity mismatch")
        if event.get("type") not in EVENT_TYPES:
            raise ValueError(f"unknown event type {event.get('type')!r}")
        seq, mono_ns = event.get("seq"), event.get("mono_ns")
        if not isinstance(seq, int) or seq <= previous_seq:
            raise ValueError("event sequence is not strictly ordered")
        if previous_seq >= 0:
            sequence_gaps += seq - previous_seq - 1
        if not isinstance(mono_ns, int) or mono_ns < 0:
            raise ValueError("invalid monotonic timestamp")
        previous_seq = seq

    declared_loss = max(int(header.get("dropped_events", 0)), int(summary.get("dropped_events", 0)))
    if (declared_loss or sequence_gaps) and not allow_loss:
        raise ValueError(f"trace lost events: declared={declared_loss} sequence_gaps={sequence_gaps}")
    return header, events, summary


def _pair_key(event: dict, begin_type: str) -> tuple:
    return (begin_type, int(event.get("task_id", 0) or 0), event.get("phase"),
            int(event.get("reload_generation", -1)), event.get("resource"))


def validate_point_pairs(events: Iterable[dict]) -> None:
    opened: dict[tuple, list[int]] = {}
    for event in events:
        event_type = event["type"]
        if event_type in PAIRS:
            opened.setdefault(_pair_key(event, event_type), []).append(int(event["mono_ns"]))
        elif event_type in REVERSE_PAIRS:
            begin_type = REVERSE_PAIRS[event_type]
            key = _pair_key(event, begin_type)
            stack = opened.get(key)
            if not stack:
                raise ValueError(f"{event_type} without matching {begin_type}: {key}")
            start = stack.pop()
            if int(event["mono_ns"]) < start:
                raise ValueError(f"{event_type} precedes {begin_type}: {key}")
    dangling = [key for key, stack in opened.items() if stack]
    if dangling:
        raise ValueError(f"unterminated paired events: {dangling[:3]}")


def build_task_spans(events: Iterable[dict], allow_loss: bool = False) -> dict[int, Span]:
    begins: dict[int, dict] = {}
    ends: dict[int, dict] = {}
    extra_dependencies: dict[int, set[int]] = {}
    open_by_thread: dict[int, list[int]] = {}

    for event in events:
        event_type = event["type"]
        task_id = int(event.get("task_id", 0) or 0)
        thread_id = int(event.get("thread_id", -1))
        if event_type == "task_begin" and task_id:
            if task_id in begins:
                raise ValueError(f"duplicate task_begin for {task_id}")
            stack = open_by_thread.setdefault(thread_id, [])
            declared_parent = int(event.get("parent_task_id", 0) or 0)
            if stack and declared_parent != stack[-1]:
                raise ValueError(f"task {task_id} does not name open lexical parent {stack[-1]}")
            begins[task_id] = event
            stack.append(task_id)
        elif event_type == "task_end" and task_id:
            if task_id in ends:
                raise ValueError(f"duplicate task_end for {task_id}")
            stack = open_by_thread.setdefault(thread_id, [])
            if not stack or stack[-1] != task_id:
                raise ValueError(f"task {task_id} closes out of nesting order")
            stack.pop()
            ends[task_id] = event
        elif event_type == "blocked_on" and task_id:
            extra_dependencies.setdefault(task_id, set()).update(
                int(dep) for dep in event.get("dependency_ids", []) if int(dep) != task_id
            )

    if any(stack for stack in open_by_thread.values()):
        raise ValueError("unterminated nested task")
    if set(begins) != set(ends):
        raise ValueError("task_begin/task_end mismatch")

    spans: dict[int, Span] = {}
    for task_id, begin in begins.items():
        end = ends[task_id]
        start_ns, end_ns = int(begin["mono_ns"]), int(end["mono_ns"])
        if end_ns < start_ns:
            raise ValueError(f"task {task_id} ends before it begins")
        deps = {int(dep) for dep in begin.get("dependency_ids", []) if int(dep) != task_id}
        deps.update(extra_dependencies.get(task_id, ()))
        cpu_value = end.get("cpu_ns")
        spans[task_id] = Span(task_id, start_ns, end_ns, begin.get("phase"),
                int(begin.get("parent_task_id", 0) or 0) or None, tuple(sorted(deps)),
                int(cpu_value) if isinstance(cpu_value, int) and cpu_value >= 0 else None)

    for span in spans.values():
        if span.parent_task_id is not None and span.parent_task_id not in spans and not allow_loss:
            raise ValueError(f"task {span.task_id} references missing parent {span.parent_task_id}")
        for dep in span.dependency_ids:
            if dep not in spans and not allow_loss:
                raise ValueError(f"task {span.task_id} references missing dependency {dep}")
    return spans


def union_ns(intervals: Iterable[tuple[int, int]]) -> int:
    ordered = sorted((start, end) for start, end in intervals if end > start)
    if not ordered:
        return 0
    total = 0
    current_start, current_end = ordered[0]
    for start, end in ordered[1:]:
        if start <= current_end:
            current_end = max(current_end, end)
        else:
            total += current_end - current_start
            current_start, current_end = start, end
    return total + current_end - current_start


def critical_path(spans: dict[int, Span]) -> tuple[list[int], int]:
    memo: dict[int, tuple[list[int], tuple[tuple[int, int], ...], int]] = {}
    visiting: set[int] = set()

    def best(task_id: int) -> tuple[list[int], tuple[tuple[int, int], ...], int]:
        if task_id in memo:
            return memo[task_id]
        if task_id in visiting:
            raise ValueError(f"dependency cycle at task {task_id}")
        visiting.add(task_id)
        span = spans[task_id]
        candidates = [([], tuple(), 0)]
        for dep in span.dependency_ids:
            if dep in spans:
                candidates.append(best(dep))
        pred_path, pred_intervals, _ = max(candidates, key=lambda item: item[2])
        intervals = pred_intervals + ((span.start_ns, span.end_ns),)
        result = (pred_path + [task_id], intervals, union_ns(intervals))
        visiting.remove(task_id)
        memo[task_id] = result
        return result

    if not spans:
        return [], 0
    path, _intervals, wall_ns = max((best(task_id) for task_id in spans), key=lambda item: item[2])
    return path, wall_ns


def summarize(path: Path, allow_loss: bool = False) -> dict:
    header, events, summary = load_trace(path, allow_loss=allow_loss)
    validate_point_pairs(events)
    spans = build_task_spans(events, allow_loss=allow_loss)
    path_ids, critical_ns = critical_path(spans)
    inclusive_ns = sum(span.wall_ns for span in spans.values())
    cpu_values = [span.cpu_ns for span in spans.values() if span.cpu_ns is not None]
    return {
        "schema_version": header["schema_version"],
        "jvm_id": header["jvm_id"],
        "pid": header["pid"],
        "measurement_origin": header.get("measurement_origin"),
        "endpoint": header.get("endpoint"),
        "event_count": len(events),
        "dropped_events": summary.get("dropped_events", 0),
        "task_count": len(spans),
        "reported_task_cpu_sum_ms": round(sum(cpu_values) / 1_000_000.0, 3),
        "reported_task_cpu_count": len(cpu_values),
        "inclusive_task_wall_ms": round(inclusive_ns / 1_000_000.0, 3),
        "critical_path_wall_ms": round(critical_ns / 1_000_000.0, 3),
        "critical_path_task_ids": path_ids,
        "critical_path": [{"task_id": task_id, "phase": spans[task_id].phase} for task_id in path_ids],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", type=Path)
    parser.add_argument("--allow-loss", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = summarize(args.trace, allow_loss=args.allow_loss)
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print(f"JVM: {result['jvm_id']} pid={result['pid']}")
        print(f"origin/endpoint: {result['measurement_origin']} / {result['endpoint']}")
        print(f"tasks: {result['task_count']} events: {result['event_count']} dropped: {result['dropped_events']}")
        print(f"reported task CPU sum: {result['reported_task_cpu_sum_ms']:.3f} ms ({result['reported_task_cpu_count']} tasks)")
        print(f"inclusive task wall: {result['inclusive_task_wall_ms']:.3f} ms")
        print(f"critical-path union wall: {result['critical_path_wall_ms']:.3f} ms")
        print("critical path: " + " -> ".join(str(task_id) for task_id in result["critical_path_task_ids"]))


if __name__ == "__main__":
    main()

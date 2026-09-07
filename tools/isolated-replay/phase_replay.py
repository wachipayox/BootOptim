#!/usr/bin/env python3
"""Replay a captured resource/model phase without launching Minecraft.

The fixture contains measured task durations and dependency edges. This tool is
deliberately a deterministic scheduling model, not a replacement for Minecraft's
SimpleReloadInstance. It is useful for rejecting executor policies cheaply; any
surviving policy still needs an exact-pack startup gate.
"""

from __future__ import annotations

import argparse
import heapq
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SCHEMA = 1


@dataclass(frozen=True)
class Task:
    task_id: str
    duration_ms: float
    dependencies: tuple[str, ...]
    order: int


def _number(value: Any, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field} must be a number")
    result = float(value)
    if not math.isfinite(result) or result < 0:
        raise ValueError(f"{field} must be finite and non-negative")
    return result


def load_fixture(path: Path) -> tuple[str, str | None, list[Task], dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != SCHEMA:
        raise ValueError(f"unsupported fixture schema: {data.get('schema')!r}")
    fixture_id = data.get("fixture_id")
    if not isinstance(fixture_id, str) or not fixture_id:
        raise ValueError("fixture_id must be a non-empty string")
    variant = data.get("variant")
    if variant is not None and not isinstance(variant, str):
        raise ValueError("variant must be a string when present")
    raw_tasks = data.get("tasks")
    if not isinstance(raw_tasks, list) or not raw_tasks:
        raise ValueError("tasks must be a non-empty array")

    tasks: list[Task] = []
    seen: set[str] = set()
    for index, raw in enumerate(raw_tasks):
        if not isinstance(raw, dict):
            raise ValueError(f"tasks[{index}] must be an object")
        task_id = raw.get("id")
        if not isinstance(task_id, str) or not task_id:
            raise ValueError(f"tasks[{index}].id must be a non-empty string")
        if task_id in seen:
            raise ValueError(f"duplicate task id: {task_id}")
        seen.add(task_id)
        dependencies = raw.get("depends_on", [])
        if not isinstance(dependencies, list) or any(not isinstance(item, str) for item in dependencies):
            raise ValueError(f"tasks[{index}].depends_on must be an array of strings")
        if len(set(dependencies)) != len(dependencies):
            raise ValueError(f"tasks[{index}].depends_on contains duplicates")
        order = raw.get("order", index)
        if isinstance(order, bool) or not isinstance(order, int):
            raise ValueError(f"tasks[{index}].order must be an integer")
        tasks.append(
            Task(
                task_id=task_id,
                duration_ms=_number(raw.get("duration_ms"), f"tasks[{index}].duration_ms"),
                dependencies=tuple(dependencies),
                order=order,
            )
        )

    task_ids = {task.task_id for task in tasks}
    for task in tasks:
        missing = sorted(set(task.dependencies) - task_ids)
        if missing:
            raise ValueError(f"{task.task_id} depends on unknown task(s): {', '.join(missing)}")
    return fixture_id, variant, tasks, data.get("metadata", {})


def _ready_key(task: Task, policy: str, downstream: dict[str, float]) -> tuple[float | int, int, str]:
    if policy == "critical-first":
        return (-downstream[task.task_id], task.order, task.task_id)
    if policy == "small-first":
        return (task.duration_ms, task.order, task.task_id)
    return (task.order, 0, task.task_id)


def _critical_path(tasks: list[Task]) -> tuple[float, list[str]]:
    by_id = {task.task_id: task for task in tasks}
    end: dict[str, float] = {}
    path: dict[str, list[str]] = {}
    pending = {task.task_id: set(task.dependencies) for task in tasks}
    completed: set[str] = set()
    while pending:
        ready = sorted(
            (by_id[task_id] for task_id, deps in pending.items() if not deps),
            key=lambda task: (task.order, task.task_id),
        )
        if not ready:
            raise ValueError("task dependency graph contains a cycle")
        completed_this_round: set[str] = set()
        for task in ready:
            starts = [end[dependency] for dependency in task.dependencies]
            start = max(starts, default=0.0)
            end[task.task_id] = start + task.duration_ms
            parent = max(task.dependencies, key=lambda dependency: end[dependency], default=None)
            path[task.task_id] = (path[parent] if parent else []) + [task.task_id]
            del pending[task.task_id]
            completed.add(task.task_id)
            completed_this_round.add(task.task_id)
        for dependencies in pending.values():
            dependencies.difference_update(completed_this_round)
    final = max(end, key=end.__getitem__)
    return end[final], path[final]


def _downstream_spans(tasks: list[Task]) -> dict[str, float]:
    dependents: dict[str, list[str]] = {task.task_id: [] for task in tasks}
    by_id = {task.task_id: task for task in tasks}
    for task in tasks:
        for dependency in task.dependencies:
            dependents[dependency].append(task.task_id)
    _, critical_path = _critical_path(tasks)
    # The topological order is recovered from the critical-path helper's
    # dependency algorithm, then evaluated in reverse using a simple Kahn pass.
    remaining = {task.task_id: set(task.dependencies) for task in tasks}
    order: list[str] = []
    while remaining:
        ready = sorted(task_id for task_id, deps in remaining.items() if not deps)
        if not ready:
            raise ValueError("task dependency graph contains a cycle")
        order.extend(ready)
        for task_id in ready:
            del remaining[task_id]
        for deps in remaining.values():
            deps.difference_update(ready)
    downstream: dict[str, float] = {}
    for task_id in reversed(order):
        children = dependents[task_id]
        downstream[task_id] = by_id[task_id].duration_ms + max(
            (downstream[child] for child in children), default=0.0
        )
    # Keep the local variable explicit so a malformed/unused path cannot make
    # the policy silently depend on a different graph calculation.
    if not critical_path:
        raise ValueError("task graph has no terminal path")
    return downstream


def replay(tasks: list[Task], workers: int, policy: str = "stock") -> dict[str, Any]:
    if workers < 1:
        raise ValueError("workers must be at least 1")
    if policy not in {"stock", "critical-first", "small-first"}:
        raise ValueError(f"unsupported policy: {policy}")
    by_id = {task.task_id: task for task in tasks}
    downstream = _downstream_spans(tasks)
    dependents: dict[str, list[str]] = {task.task_id: [] for task in tasks}
    remaining = {task.task_id: set(task.dependencies) for task in tasks}
    for task in tasks:
        for dependency in task.dependencies:
            dependents[dependency].append(task.task_id)

    ready: list[tuple[float | int, int, str]] = []
    for task in tasks:
        if not remaining[task.task_id]:
            heapq.heappush(ready, _ready_key(task, policy, downstream))
    available_workers = [(0.0, worker_id) for worker_id in range(workers)]
    heapq.heapify(available_workers)
    running: list[tuple[float, int, str]] = []
    completed_at: dict[str, float] = {}
    records: dict[str, dict[str, Any]] = {}
    now = 0.0

    while ready or running:
        assigned = False
        while ready and available_workers and available_workers[0][0] <= now:
            _, worker_id = heapq.heappop(available_workers)
            task_id = heapq.heappop(ready)[-1]
            task = by_id[task_id]
            dependency_ready = max((completed_at[item] for item in task.dependencies), default=0.0)
            start = max(now, dependency_ready)
            end = start + task.duration_ms
            records[task_id] = {
                "worker": worker_id,
                "start_ms": start,
                "end_ms": end,
                "queue_wait_ms": max(0.0, start - dependency_ready),
            }
            heapq.heappush(running, (end, worker_id, task_id))
            assigned = True

        if running:
            now = running[0][0]
            finished: list[tuple[float, int, str]] = []
            while running and running[0][0] <= now:
                finished.append(heapq.heappop(running))
            for end, worker_id, task_id in finished:
                completed_at[task_id] = end
                heapq.heappush(available_workers, (end, worker_id))
            for _, _, task_id in finished:
                for dependent in dependents[task_id]:
                    remaining[dependent].discard(task_id)
                    if not remaining[dependent]:
                        heapq.heappush(ready, _ready_key(by_id[dependent], policy, downstream))
        elif ready and not assigned:
            # A worker is busy in the future; advance to its earliest release.
            now = available_workers[0][0]

    critical_ms, critical_path = _critical_path(tasks)
    total_work_ms = sum(task.duration_ms for task in tasks)
    makespan_ms = max((record["end_ms"] for record in records.values()), default=0.0)
    queue_wait_ms = sum(record["queue_wait_ms"] for record in records.values())
    return {
        "workers": workers,
        "policy": policy,
        "task_count": len(tasks),
        "total_work_ms": total_work_ms,
        "critical_path_ms": critical_ms,
        "critical_path": critical_path,
        "makespan_ms": makespan_ms,
        "queue_wait_ms": queue_wait_ms,
        "parallelism_speedup_vs_single": (total_work_ms / makespan_ms) if makespan_ms else None,
        "critical_path_ratio": (critical_ms / makespan_ms) if makespan_ms else None,
        "tasks": records,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument(
        "--policy",
        choices=("stock", "critical-first", "small-first"),
        default="stock",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    fixture_id, variant, tasks, metadata = load_fixture(args.fixture)
    result = {
        "schema": SCHEMA,
        "kind": "phase-replay",
        "fixture_id": fixture_id,
        "variant": variant,
        "metadata": metadata,
        **replay(tasks, args.workers, args.policy),
    }
    text = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")


if __name__ == "__main__":
    main()

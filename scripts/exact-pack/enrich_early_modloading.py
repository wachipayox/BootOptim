#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

TRACE_SCHEMA = "bootoptim.boottrace"
TRACE_SCHEMA_VERSION = 1
TRACE_ORIGIN = "hosted_exact_pack"
TRACE_ENDPOINT = "main_menu"
REQUIRED_TASKS = ("root_mod_discovery", "dependency_discovery")
REQUIRED_PHASES = ("modlauncher_transformers_to_minecraft_bootstrap",)


def read_jsonl(path: Path):
    records = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            text = line.strip()
            if not text:
                continue
            try:
                records.append(json.loads(text))
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSONL at {path}:{line_no}: {exc}") from exc
    return records


def one(records, record_type):
    matches = [r for r in records if r.get("record") == record_type]
    if len(matches) != 1:
        raise ValueError(f"expected exactly one {record_type}, found {len(matches)}")
    return matches[0]


def collect_intervals(events, event_kind, names):
    intervals = {}
    for name in names:
        begins = [e for e in events if e.get("type") == f"{event_kind}_begin" and e.get("phase") == name]
        ends = [e for e in events if e.get("type") == f"{event_kind}_end" and e.get("phase") == name]
        if len(begins) != 1 or len(ends) != 1:
            raise ValueError(f"expected one {event_kind} pair for {name}; begins={len(begins)} ends={len(ends)}")
        begin, end = begins[0], ends[0]
        if event_kind == "task" and begin.get("task_id") != end.get("task_id"):
            raise ValueError(f"task id mismatch for {name}")
        start_ns, end_ns = int(begin["mono_ns"]), int(end["mono_ns"])
        if end_ns < start_ns:
            raise ValueError(f"negative interval for {name}")
        intervals[name] = {"start_ns": start_ns, "end_ns": end_ns, "ms": (end_ns - start_ns) / 1_000_000.0}
    return intervals


def enrich(result_path: Path, trace_path: Path, require_resource_contract: bool):
    result = json.loads(result_path.read_text(encoding="utf-8"))
    if require_resource_contract and result.get("resource_contract_valid") is not True:
        raise ValueError("resource contract invalid: early-modloading matrix point rejected")
    if result.get("mod_entrypoint_ms") is None:
        raise ValueError("missing mod_entrypoint_ms")

    records = read_jsonl(trace_path)
    header = one(records, "trace_header")
    summary = one(records, "trace_summary")
    if header.get("schema") != TRACE_SCHEMA or header.get("schema_version") != TRACE_SCHEMA_VERSION:
        raise ValueError(f"unexpected trace schema: {header.get('schema')} v{header.get('schema_version')}")
    if header.get("measurement_origin") != TRACE_ORIGIN:
        raise ValueError(f"unexpected trace origin: {header.get('measurement_origin')}")
    if header.get("endpoint") != TRACE_ENDPOINT:
        raise ValueError(f"unexpected trace endpoint: {header.get('endpoint')}")
    for field in ("dropped_events", "flush_failures", "development_sink_failures"):
        if int(summary.get(field, 0)) != 0:
            raise ValueError(f"trace health failure: {field}={summary.get(field)}")
    if int(summary.get("event_counters", {}).get("error", 0)) != 0:
        raise ValueError("trace contains error events")

    events = [r for r in records if r.get("record") == "event"]
    tasks = collect_intervals(events, "task", REQUIRED_TASKS)
    phases = collect_intervals(events, "phase", REQUIRED_PHASES)
    dep = tasks["dependency_discovery"]
    transform = phases["modlauncher_transformers_to_minecraft_bootstrap"]
    if transform["start_ns"] < dep["end_ns"]:
        raise ValueError("transform phase begins before dependency discovery ends")

    metrics = {
        "schema": "bootoptim.early_modloading.v1",
        "measurement_origin": TRACE_ORIGIN,
        "endpoint": TRACE_ENDPOINT,
        "trace_schema_version": TRACE_SCHEMA_VERSION,
        "trace_health_valid": True,
        "root_mod_discovery_ms": round(tasks["root_mod_discovery"]["ms"], 6),
        "dependency_discovery_ms": round(dep["ms"], 6),
        "dependency_to_transform_gap_ms": round((transform["start_ns"] - dep["end_ns"]) / 1_000_000.0, 6),
        "transform_to_bootstrap_ms": round(transform["ms"], 6),
        "mod_entrypoint_ms": result["mod_entrypoint_ms"],
    }

    # Optional same-trace causal children. They remain inclusive walls, never summed as savings.
    optional_tasks = ("minecraft_bootstrap", "fml_gather_and_initialize_mods")
    for name in optional_tasks:
        begins = [e for e in events if e.get("type") == "task_begin" and e.get("phase") == name]
        ends = [e for e in events if e.get("type") == "task_end" and e.get("phase") == name]
        if len(begins) == 1 and len(ends) == 1 and begins[0].get("task_id") == ends[0].get("task_id"):
            metrics[f"{name}_ms"] = round((int(ends[0]["mono_ns"]) - int(begins[0]["mono_ns"])) / 1_000_000.0, 6)

    manifest = result.get("scaling_variant_manifest")
    if isinstance(manifest, dict):
        selected = manifest.get("selected_artifacts")
        roots = manifest.get("roots")
        if isinstance(selected, list):
            metrics["selected_artifact_count"] = len(selected)
        if isinstance(roots, list):
            metrics["root_count"] = len(roots)
        metrics["source_pack_fingerprint"] = manifest.get("source_pack_fingerprint")
        metrics["variant_kind"] = manifest.get("variant_kind")
        metrics["variant_id"] = manifest.get("variant_id")

    result["early_modloading"] = metrics
    result_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(metrics, indent=2, sort_keys=True))
    return metrics


def main():
    parser = argparse.ArgumentParser(description="Validate and attach same-run early-modloading trace metrics to an exact-pack result.")
    parser.add_argument("--result", type=Path, default=Path("result.json"))
    parser.add_argument("--trace", type=Path, default=Path("run-pack-benchmark/logs/bootoptim-trace.jsonl"))
    parser.add_argument("--require-resource-contract", action="store_true")
    args = parser.parse_args()
    enrich(args.result, args.trace, args.require_resource_contract)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

REQUIRED_ONCE = {
    "complete_scan", "stage2_validation", "validate_languages", "sorter_total",
    "add_access_transformers", "add_mixin_configs", "add_enum_extenders",
    "background_scan_ctor", "add_for_scanning", "scan_register_loading_list", "scan_wait",
}

def ms(ns):
    return None if ns is None or ns < 0 else ns / 1_000_000.0

def union_ns(intervals):
    if not intervals: return 0
    intervals = sorted(intervals)
    total = 0
    s, e = intervals[0]
    for ns, ne in intervals[1:]:
        if ns <= e: e = max(e, ne)
        else: total += max(0, e - s); s, e = ns, ne
    return total + max(0, e - s)

def load_trace(path):
    events = []
    for line_no, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip(): continue
        try: events.append(json.loads(line))
        except Exception as exc: raise SystemExit(f"invalid JSONL line {line_no}: {exc}")
    return events

def exclusive(event, children):
    own = int(event["duration_ns"])
    child_wall = union_ns([(max(int(c["start_ns"]), int(event["start_ns"])), min(int(c["end_ns"]), int(event["end_ns"]))) for c in children])
    wall = max(0, own - child_wall)
    cpu = int(event.get("cpu_ns", -1))
    child_cpu = sum(max(0, int(c.get("cpu_ns", -1))) for c in children if int(c.get("cpu_ns", -1)) >= 0)
    cpu_ex = None if cpu < 0 else max(0, cpu - child_cpu)
    return wall, cpu_ex

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", required=True)
    ap.add_argument("--startup")
    ap.add_argument("--json-output", required=True)
    ap.add_argument("--markdown-output", required=True)
    args = ap.parse_args()
    events = load_trace(args.trace)
    headers = [e for e in events if e.get("kind") == "profile_header"]
    if len(headers) != 1 or "accepted=true" not in str(headers[0].get("detail")):
        raise SystemExit("fail-closed: expected exactly one accepted FML profile header")
    h = headers[0]
    if h.get("module_name") != "fml_loader" or h.get("module_version") != "4.0.43":
        raise SystemExit(f"fail-closed: unexpected FML identity {h.get('module_name')}@{h.get('module_version')}")
    measured = [e for e in events if e.get("kind") not in {"profile_header", "profile_disabled"}]
    counts = Counter(e["kind"] for e in measured)
    missing = sorted(k for k in REQUIRED_ONCE if counts[k] != 1)
    if missing: raise SystemExit("fail-closed: required scope count != 1: " + ", ".join(f"{k}={counts[k]}" for k in missing))
    thrown = [e for e in measured if e.get("detail")]
    if thrown: raise SystemExit("fail-closed: observed throw in diagnostic scope")

    by_id = {int(e["seq"]): e for e in measured}
    children = defaultdict(list)
    for e in measured:
        p = int(e.get("parent_id", 0))
        if p: children[p].append(e)
    rows = []
    for e in measured:
        direct = [c for c in children[int(e["seq"])] if int(c.get("tid", -1)) == int(e.get("tid", -2))]
        ex_wall, ex_cpu = exclusive(e, direct)
        rows.append({
            "id": int(e["seq"]), "parent_id": int(e.get("parent_id", 0)), "depth": int(e.get("depth", 0)),
            "kind": e["kind"], "thread": e.get("thread"), "wall_ms": ms(int(e["duration_ns"])),
            "cpu_ms": ms(int(e.get("cpu_ns", -1))), "exclusive_wall_ms": ms(ex_wall), "exclusive_cpu_ms": ms(ex_cpu),
        })

    stage2 = next(r for r in rows if r["kind"] == "stage2_validation")
    stage2_children = [r for r in rows if r["parent_id"] == stage2["id"]]
    expected_stage2_children = {"validate_languages", "sorter_total", "add_access_transformers", "add_mixin_configs", "add_enum_extenders", "background_scan_ctor", "add_for_scanning"}
    got = {r["kind"] for r in stage2_children}
    if not expected_stage2_children.issubset(got):
        raise SystemExit("fail-closed: Stage2 direct-child hierarchy incomplete: " + repr(sorted(got)))

    compiles = [e for e in measured if e["kind"] == "scan_compile_content"]
    submits = [e for e in measured if e["kind"] == "scan_submit"]
    if not compiles or not submits:
        raise SystemExit("fail-closed: no scan worker/submission events")
    worker_cpu = sum(max(0, int(e.get("cpu_ns", -1))) for e in compiles if int(e.get("cpu_ns", -1)) >= 0)
    worker_union = union_ns([(int(e["start_ns"]), int(e["end_ns"])) for e in compiles])
    worker_span = max(int(e["end_ns"]) for e in compiles) - min(int(e["start_ns"]) for e in compiles)
    wait = next(r for r in rows if r["kind"] == "scan_wait")

    summary = {
        "fml": "fml_loader@4.0.43",
        "event_counts": dict(sorted(counts.items())),
        "stage2": stage2,
        "stage2_direct_children": stage2_children,
        "scan": {
            "submit_count": len(submits), "compile_count": len(compiles),
            "worker_cpu_ms_sum": ms(worker_cpu), "worker_wall_union_ms": ms(worker_union), "worker_span_ms": ms(worker_span),
            "wait": wait,
        },
        "rows": rows,
        "note": "Inclusive scopes are never summed for attribution; exclusive values subtract direct same-thread child interval unions. Worker CPU is reported separately from Stage2 wall.",
    }
    Path(args.json_output).write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    lines = ["# FML 4.0.43 Stage-2 diagnostic", "", "Inclusive scopes are shown for context; attribution uses exclusive wall/CPU so nested scopes are not double-counted.", "", "## Stage 2", "", "| scope | wall ms | CPU ms | exclusive wall ms | exclusive CPU ms |", "|---|---:|---:|---:|---:|"]
    ordered = [stage2] + sorted(stage2_children, key=lambda r: r["id"])
    for r in ordered:
        cpu = "n/a" if r["cpu_ms"] is None else f"{r['cpu_ms']:.3f}"
        excpu = "n/a" if r["exclusive_cpu_ms"] is None else f"{r['exclusive_cpu_ms']:.3f}"
        lines.append(f"| `{r['kind']}` | {r['wall_ms']:.3f} | {cpu} | {r['exclusive_wall_ms']:.3f} | {excpu} |")
    lines += ["", "## Background scan", "", f"- submissions: **{len(submits)}**", f"- worker `compileContent` calls: **{len(compiles)}**", f"- worker CPU sum: **{ms(worker_cpu):.3f} ms**", f"- worker wall union: **{ms(worker_union):.3f} ms** (span {ms(worker_span):.3f} ms)", f"- later `waitForScanToComplete`: **{wait['wall_ms']:.3f} ms wall / {wait['cpu_ms']:.3f} ms CPU**", "", "The scan wait is outside `stage2Validation`; it is not added to Stage-2 wall."]
    Path(args.markdown_output).write_text("\n".join(lines) + "\n", encoding="utf-8")

if __name__ == "__main__": main()

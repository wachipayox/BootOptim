#!/usr/bin/env python3
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

DEP_RE = re.compile(r"BOOTOPTIM_STARTUP phase=dependency_discovery_end .*?elapsed_ms=([0-9.]+)")
START_SUFFIX = "DependencyDiscoveryStartLocator"
END_SUFFIX = "DependencyDiscoveryEndLocator"


def ms(ns):
    return ns / 1_000_000.0


def union_ns(events):
    spans = sorted((e["start_ns"], e["end_ns"]) for e in events if e["end_ns"] >= e["start_ns"])
    total = 0
    cur_s = cur_e = None
    for s, e in spans:
        if cur_s is None:
            cur_s, cur_e = s, e
        elif s <= cur_e:
            cur_e = max(cur_e, e)
        else:
            total += cur_e - cur_s
            cur_s, cur_e = s, e
    if cur_s is not None:
        total += cur_e - cur_s
    return total


def contained(children, parent):
    return [e for e in children if e is not parent and parent["tid"] == e["tid"]
            and parent["start_ns"] <= e["start_ns"] and e["end_ns"] <= parent["end_ns"]]


def is_sentinel(event):
    owner = event.get("owner") or ""
    return owner.endswith(START_SUFFIX) or owner.endswith(END_SUFFIX)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", required=True)
    ap.add_argument("--startup", required=True)
    ap.add_argument("--json-output", required=True)
    ap.add_argument("--markdown-output", required=True)
    args = ap.parse_args()

    events = [json.loads(line) for line in Path(args.trace).read_text(encoding="utf-8").splitlines() if line.strip()]
    headers = [e for e in events if e.get("kind") == "profile_header"]
    disabled = [e for e in events if e.get("kind") == "profile_disabled"]
    if len(headers) != 1 or disabled or "accepted=true" not in (headers[0].get("detail") or ""):
        raise SystemExit("invalid/mismatched FML profile header")
    measured = [e for e in events if e.get("kind") not in {"profile_header", "profile_disabled"}]
    thrown = [e for e in measured if e.get("detail")]
    if thrown:
        raise SystemExit(f"instrumented method threw unexpectedly: {thrown[:3]}")

    startup = Path(args.startup).read_text(encoding="utf-8", errors="replace")
    dep_matches = DEP_RE.findall(startup)
    if len(dep_matches) != 1:
        raise SystemExit(f"expected one dependency_discovery_end marker, got {len(dep_matches)}")
    dependency_wall_ms = float(dep_matches[0])

    all_locators = sorted((e for e in measured if e["kind"] == "dependency_locator"), key=lambda e: (e["start_ns"], -e["end_ns"]))
    locators = [e for e in all_locators if not is_sentinel(e)]
    if not locators:
        raise SystemExit("no real dependency locator events")
    tids = {e["tid"] for e in locators}

    top_level = [e for e in locators if not any(e in contained(locators, parent) for parent in locators if parent is not e)]
    top_level = sorted(top_level, key=lambda e: e["start_ns"])
    overlaps = []
    for prev, cur in zip(top_level, top_level[1:]):
        if prev["tid"] == cur["tid"] and cur["start_ns"] < prev["end_ns"]:
            overlaps.append((prev["owner"], cur["owner"]))
    if overlaps:
        raise SystemExit(f"top-level locator intervals overlap on one thread: {overlaps[:3]}")

    nesting = []
    event_exclusive_ns = {}
    for e in locators:
        children = contained(locators, e)
        event_exclusive_ns[e["seq"]] = max(0, e["duration_ns"] - union_ns(children))
        for child in children:
            if not any(child in contained(locators, mid) for mid in children if mid is not child):
                nesting.append({"parent": e["owner"], "child": child["owner"], "wall_ms": ms(child["duration_ns"])})

    by_owner = defaultdict(list)
    for e in locators:
        by_owner[e["owner"]].append(e)
    locator_rows = []
    for owner, rows in sorted(by_owner.items(), key=lambda kv: -sum(event_exclusive_ns[e["seq"]] for e in kv[1])):
        wall_ns = sum(e["duration_ns"] for e in rows)
        exclusive_wall_ns = sum(event_exclusive_ns[e["seq"]] for e in rows)
        cpu_values = [e["cpu_ns"] for e in rows if e["cpu_ns"] >= 0]
        locator_rows.append({
            "owner": owner,
            "module": rows[0].get("module_name"),
            "module_version": rows[0].get("module_version"),
            "calls": len(rows),
            "inclusive_wall_ms": ms(wall_ns),
            "exclusive_wall_ms": ms(exclusive_wall_ns),
            "cpu_ms": ms(sum(cpu_values)) if len(cpu_values) == len(rows) else None,
        })

    locator_union_ms = ms(union_ns(locators))
    locator_loop_residual_ms = dependency_wall_ms - locator_union_ms

    kinds = defaultdict(list)
    for e in measured:
        kinds[e["kind"]].append(e)

    scope_rows = []
    for kind in ["discover_total", "unique_list", "stage1_validation", "identify_mods",
                 "stage2_validation", "identify_language", "sorter_total", "dependency_versions",
                 "graph_sort", "topological_sort"]:
        rows = kinds.get(kind, [])
        if not rows:
            continue
        cpu_values = [e["cpu_ns"] for e in rows if e["cpu_ns"] >= 0]
        scope_rows.append({
            "kind": kind,
            "calls": len(rows),
            "wall_union_ms": ms(union_ns(rows)),
            "wall_sum_ms": ms(sum(e["duration_ns"] for e in rows)),
            "cpu_sum_ms": ms(sum(cpu_values)) if len(cpu_values) == len(rows) else None,
            "threads": sorted({e["thread"] for e in rows}),
        })

    exclusive = {}
    child_map = {
        "stage1_validation": ["identify_mods"],
        "stage2_validation": ["identify_language", "sorter_total"],
        "sorter_total": ["unique_list", "dependency_versions", "graph_sort"],
        "graph_sort": ["topological_sort"],
    }
    for parent_kind, child_kinds in child_map.items():
        parents = kinds.get(parent_kind, [])
        if len(parents) != 1:
            continue
        p = parents[0]
        children = []
        for ck in child_kinds:
            children += contained(kinds.get(ck, []), p)
        exclusive[parent_kind] = max(0.0, ms(p["duration_ns"] - union_ns(children)))

    result = {
        "schema": 2,
        "expected_fml": "4.0.43",
        "dependency_discovery_wall_ms": dependency_wall_ms,
        "dependency_locator_union_ms": locator_union_ms,
        "dependency_locator_loop_residual_ms": locator_loop_residual_ms,
        "locator_threads": sorted(tids),
        "locator_rows": locator_rows,
        "locator_nesting": nesting,
        "scope_rows": scope_rows,
        "exclusive_wall_ms": exclusive,
        "event_count": len(measured),
    }
    Path(args.json_output).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    out = []
    out.append("# Agent136 FML dependency-resolution profile")
    out.append("")
    out.append(f"- stock dependency-discovery inclusive wall: **{dependency_wall_ms:.3f} ms**")
    out.append(f"- union of real dependency-locator callbacks: **{locator_union_ms:.3f} ms**")
    out.append(f"- loop/bookkeeping residual after locator union: **{locator_loop_residual_ms:.3f} ms**")
    out.append("")
    out.append("## Dependency locator scopes (nested-aware)")
    out.append("")
    out.append("| owner | module | calls | inclusive wall ms | exclusive wall ms | thread CPU ms |")
    out.append("| --- | --- | ---: | ---: | ---: | ---: |")
    for r in locator_rows:
        cpu = "n/a" if r["cpu_ms"] is None else f"{r['cpu_ms']:.3f}"
        module = r["module"] or "unnamed"
        if r["module_version"]:
            module += "@" + r["module_version"]
        out.append(f"| `{r['owner']}` | `{module}` | {r['calls']} | {r['inclusive_wall_ms']:.3f} | {r['exclusive_wall_ms']:.3f} | {cpu} |")
    if nesting:
        out.append("")
        out.append("Observed nested locator calls (already included in parent wall):")
        for n in nesting:
            out.append(f"- `{n['parent']}` → `{n['child']}`: **{n['wall_ms']:.3f} ms**")
    out.append("")
    out.append("## FML validation / graph scopes")
    out.append("")
    out.append("| scope | calls | union wall ms | summed wall ms | thread CPU ms |")
    out.append("| --- | ---: | ---: | ---: | ---: |")
    for r in scope_rows:
        cpu = "n/a" if r["cpu_sum_ms"] is None else f"{r['cpu_sum_ms']:.3f}"
        out.append(f"| `{r['kind']}` | {r['calls']} | {r['wall_union_ms']:.3f} | {r['wall_sum_ms']:.3f} | {cpu} |")
    if exclusive:
        out.append("")
        out.append("Exclusive parent residuals subtract only the union of named nested child intervals:")
        for k, v in exclusive.items():
            out.append(f"- `{k}` exclusive residual: **{v:.3f} ms**")
    Path(args.markdown_output).write_text("\n".join(out) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

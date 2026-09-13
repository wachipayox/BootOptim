#!/usr/bin/env python3
import argparse
import json
from collections import defaultdict
from pathlib import Path

KINDS = [
    "add_access_transformers",
    "access_transformer_file",
    "add_mixin_configs",
    "mixin_config_queue",
    "add_enum_extenders",
    "enum_prototypes_total",
    "enum_prototype_file",
]


def ms(ns):
    return ns / 1_000_000.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", required=True)
    parser.add_argument("--json-output", required=True)
    parser.add_argument("--markdown-output", required=True)
    args = parser.parse_args()

    events = [json.loads(line) for line in Path(args.trace).read_text().splitlines() if line.strip()]
    headers = [e for e in events if e.get("kind") == "profile_header"]
    if len(headers) != 1 or "accepted=true" not in (headers[0].get("detail") or ""):
        raise SystemExit(f"expected one accepted FML 4.0.43 header, got {headers}")

    by_kind = defaultdict(list)
    for event in events:
        kind = event.get("kind")
        if kind in KINDS:
            by_kind[kind].append(event)
            if event.get("detail") and "throw=" in event["detail"]:
                raise SystemExit(f"instrumented stock path threw: {event}")

    for top in ("add_access_transformers", "add_mixin_configs", "add_enum_extenders"):
        if len(by_kind[top]) != 1:
            raise SystemExit(f"expected exactly one {top} event, got {len(by_kind[top])}")
    if len(by_kind["enum_prototypes_total"]) > 1:
        raise SystemExit(f"expected at most one enum_prototypes_total event, got {len(by_kind['enum_prototypes_total'])}")

    summary = {}
    for kind in KINDS:
        rows = by_kind[kind]
        summary[kind] = {
            "count": len(rows),
            "wall_ms": ms(sum(e["duration_ns"] for e in rows)),
            "cpu_ms": ms(sum(e["cpu_ns"] for e in rows if e.get("cpu_ns", -1) >= 0)),
        }

    at_residual = summary["add_access_transformers"]["wall_ms"] - summary["access_transformer_file"]["wall_ms"]
    mixin_residual = summary["add_mixin_configs"]["wall_ms"] - summary["mixin_config_queue"]["wall_ms"]
    enum_combined_residual = summary["add_enum_extenders"]["wall_ms"] - summary["enum_prototype_file"]["wall_ms"]
    total_top = (summary["add_access_transformers"]["wall_ms"]
                 + summary["add_mixin_configs"]["wall_ms"]
                 + summary["add_enum_extenders"]["wall_ms"])

    derived = {
        "three_methods_serial_wall_ms": total_top,
        "access_transformer_collection_residual_ms": at_residual,
        "mixin_collection_filter_residual_ms": mixin_residual,
        "enum_collection_sort_group_publication_residual_ms": enum_combined_residual,
    }
    enum_inner_available = summary["enum_prototypes_total"]["count"] == 1
    if enum_inner_available:
        derived["enum_path_collection_residual_ms"] = (
            summary["add_enum_extenders"]["wall_ms"] - summary["enum_prototypes_total"]["wall_ms"])
        derived["enum_sort_group_duplicate_residual_ms"] = (
            summary["enum_prototypes_total"]["wall_ms"] - summary["enum_prototype_file"]["wall_ms"])

    result = {
        "fml_module": headers[0].get("module_name"),
        "fml_version": headers[0].get("module_version"),
        "enum_inner_hook_available": enum_inner_available,
        "scopes": summary,
        "derived": derived,
    }
    Path(args.json_output).write_text(json.dumps(result, indent=2) + "\n")

    lines = [
        "# Agent139 FML 4.0.43 registration profile",
        "",
        f"Named module: `{result['fml_module']}@{result['fml_version']}`",
        "",
        "| scope | count | wall ms | thread CPU ms |",
        "|---|---:|---:|---:|",
    ]
    for kind in KINDS:
        row = summary[kind]
        lines.append(f"| `{kind}` | {row['count']} | {row['wall_ms']:.3f} | {row['cpu_ms']:.3f} |")
    lines += [
        "",
        "## Derived serial residuals",
        "",
        f"- Sum of the three requested top-level methods: **{total_top:.3f} ms wall**.",
        f"- `addAccessTransformers` outside per-file `FMLLoader.addAccessTransformer`: **{at_residual:.3f} ms**.",
        f"- `addMixinConfigs` outside actual queue calls (iteration + required-mod filtering/debug): **{mixin_residual:.3f} ms**.",
        f"- `addEnumExtenders` outside the three per-file JSON loads: **{enum_combined_residual:.3f} ms**. This is a conservative combined bound for config/path collection plus prototype flatten/sort/group/duplicate checking/publication.",
    ]
    if enum_inner_available:
        lines += [
            f"- Of that enum residual, path/config collection outside `loadEnumPrototypes`: **{derived['enum_path_collection_residual_ms']:.3f} ms**.",
            f"- `loadEnumPrototypes` outside per-file JSON load/validation: **{derived['enum_sort_group_duplicate_residual_ms']:.3f} ms**.",
        ]
    else:
        lines.append("- The optional `loadEnumPrototypes` inner Advice did not bind in this runtime, so the analyzer intentionally does not invent a finer split.")
    lines += [
        "",
        "These are diagnostic timings only; they are not claimed TTMM savings.",
    ]
    Path(args.markdown_output).write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()

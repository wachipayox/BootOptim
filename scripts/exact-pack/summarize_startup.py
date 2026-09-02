#!/usr/bin/env python3
import argparse
import json
import re
import statistics
from pathlib import Path


CLOCK_RE = re.compile(
    r"^\[(?:(?:\d{2}[A-Za-z]{3}\d{4})\s+)?(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?\]"
)


def parse_clock_ms(line: str):
    match = CLOCK_RE.match(line)
    if not match:
        return None
    hour, minute, second = (int(match.group(i)) for i in range(1, 4))
    millis = int(match.group(4) or 0)
    return ((hour * 60 + minute) * 60 + second) * 1000 + millis


def delta_ms(start, end):
    if start is None or end is None:
        return None
    if end < start:
        end += 24 * 60 * 60 * 1000
    return end - start


def find_time(lines, needle):
    for line in lines:
        if needle in line:
            value = parse_clock_ms(line)
            if value is not None:
                return value
    return None


def find_first_time(lines, needles):
    for line in lines:
        if any(needle in line for needle in needles):
            value = parse_clock_ms(line)
            if value is not None:
                return value
    return None


def parse_startup_report(path: Path):
    phases = {}
    total = None
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = re.match(r"PHASE name=(\S+) uptime_ms=(\d+)", line)
        if match:
            phases[match.group(1)] = int(match.group(2))
        match = re.match(r"SUMMARY .* total_startup_ms=(\d+) status=(\S+)", line)
        if match:
            total = int(match.group(1))
    return phases, total


def find_int(line: str, key: str):
    match = re.search(rf"(?:^|\s){re.escape(key)}=(\d+)(?:\s|$)", line)
    return int(match.group(1)) if match else None


def parse_decocraft(lines):
    markers = [line.strip() for line in lines if "BOOTOPTIM_DECOCRAFT_3D_ITEMS" in line]
    status = None
    remapped = None
    removed = None
    for line in markers:
        if " stage=" not in line and " status=" in line:
            match = re.search(r"\bstatus=(\S+)", line)
            if match:
                status = match.group(1)
        if "stage=models" in line:
            remapped = find_int(line, "remapped")
        if "stage=atlas " in line:
            removed = find_int(line, "removed")
    if status == "disabled":
        remapped = 0 if remapped is None else remapped
        removed = 0 if removed is None else removed
    return markers, status, remapped, removed


def parse_blocks_atlas(lines):
    pattern = re.compile(
        r"Created:\s*(\d+)x(\d+)x(\d+)\s+minecraft:textures/atlas/blocks\.png-atlas"
    )
    for line in lines:
        match = pattern.search(line)
        if match:
            width, height, levels = (int(match.group(i)) for i in range(1, 4))
            return width, height, levels
    return None, None, None


def parse_single(args):
    latest_path = Path(args.latest)
    startup_path = Path(args.startup)
    lines = latest_path.read_text(encoding="utf-8", errors="replace").splitlines()
    phases, total = parse_startup_report(startup_path)

    mod_entrypoint = phases.get("mod_entrypoint")
    main_menu = phases.get("main_menu", total)

    mcef_start = find_first_time(lines, ["[MCEF] Initializing CEF", "Initializing CEF on "])
    mcef_end = find_time(lines, "Chromium Embedded Framework initialized")
    reload_start = find_time(lines, "Reloading ResourceManager:")
    fancy_reload_end = find_time(lines, "Minecraft resource reload: FINISHED")

    panorama_ms = None
    panorama_patterns = [
        re.compile(r"\bpreload_ms=([0-9]+(?:\.[0-9]+)?)", re.IGNORECASE),
        re.compile(r"preload(?:ing)?[^\n]*?([0-9]+(?:[.,][0-9]+)?)\s*ms", re.IGNORECASE),
        re.compile(r"panorama[^\n]*?([0-9]+(?:[.,][0-9]+)?)\s*ms", re.IGNORECASE),
    ]
    for line in lines:
        if "FANCYMENU" not in line.upper() and "PANORAMA" not in line.upper():
            continue
        for pattern in panorama_patterns:
            match = pattern.search(line)
            if match:
                try:
                    panorama_ms = float(match.group(1).replace(",", "."))
                except ValueError:
                    pass
                break

    decocraft_markers, decocraft_status, decocraft_remapped, decocraft_removed = parse_decocraft(lines)
    atlas_width, atlas_height, atlas_levels = parse_blocks_atlas(lines)

    error_patterns = [
        "InvalidInjectionException",
        "Mixin apply for mod boot_optim failed",
        "Mixin prepare for mod boot_optim failed",
    ]
    bootoptim_mixin_errors = sum(1 for line in lines if any(pattern in line for pattern in error_patterns))

    result = {
        "variant": args.variant,
        "iteration": args.iteration,
        "startup_total_ms": total,
        "mod_entrypoint_ms": mod_entrypoint,
        "main_menu_ms": main_menu,
        "post_mod_entrypoint_ms": (main_menu - mod_entrypoint) if main_menu is not None and mod_entrypoint is not None else None,
        "mcef_init_ms": delta_ms(mcef_start, mcef_end),
        "reload_to_fancymenu_finish_ms": delta_ms(reload_start, fancy_reload_end),
        "fancymenu_panorama_ms": panorama_ms,
        "bootoptim_mixin_errors": bootoptim_mixin_errors,
        "decocraft_status": decocraft_status,
        "decocraft_models_remapped": decocraft_remapped,
        "decocraft_atlas_removed": decocraft_removed,
        "decocraft_markers": decocraft_markers,
        "blocks_atlas_width": atlas_width,
        "blocks_atlas_height": atlas_height,
        "blocks_atlas_levels": atlas_levels,
    }
    Path(args.output).write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))


def median(values):
    filtered = [value for value in values if isinstance(value, (int, float))]
    return statistics.median(filtered) if filtered else None


def format_ms(value):
    if value is None:
        return "n/a"
    return f"{value:,.1f}"


def stable_text(values):
    filtered = sorted({str(value) for value in values if value is not None})
    return ", ".join(filtered) if filtered else "n/a"


def atlas_text(row):
    width = row.get("blocks_atlas_width")
    height = row.get("blocks_atlas_height")
    levels = row.get("blocks_atlas_levels")
    if None in (width, height, levels):
        return None
    return f"{width}x{height}x{levels}"


def parse_aggregate(args):
    files = sorted(Path(args.results_dir).rglob("result.json"))
    if not files:
        markdown_text = (
            "# Exact-pack startup benchmark\n\n"
            "No successful exact-pack run produced `result.json`. Inspect the per-run console/thread-dump artifacts.\n"
        )
        Path(args.output).write_text(markdown_text, encoding="utf-8")
        Path(args.json_output).write_text(json.dumps({"status": "no_successful_runs"}, indent=2), encoding="utf-8")
        print(markdown_text)
        return

    rows = [json.loads(path.read_text(encoding="utf-8")) for path in files]
    variants = sorted({row["variant"] for row in rows})
    metrics = [
        "startup_total_ms",
        "mod_entrypoint_ms",
        "post_mod_entrypoint_ms",
        "mcef_init_ms",
        "reload_to_fancymenu_finish_ms",
        "fancymenu_panorama_ms",
    ]
    summary = {}
    for variant in variants:
        subset = [row for row in rows if row["variant"] == variant]
        summary[variant] = {metric: median([row.get(metric) for row in subset]) for metric in metrics}
        summary[variant]["runs"] = len(subset)
        summary[variant]["bootoptim_mixin_errors"] = sum(row.get("bootoptim_mixin_errors", 0) for row in subset)
        summary[variant]["decocraft_status"] = stable_text(row.get("decocraft_status") for row in subset)
        summary[variant]["decocraft_models_remapped"] = median([row.get("decocraft_models_remapped") for row in subset])
        summary[variant]["decocraft_atlas_removed"] = median([row.get("decocraft_atlas_removed") for row in subset])
        summary[variant]["blocks_atlas"] = stable_text(atlas_text(row) for row in subset)

    markdown = [
        "# Exact-pack startup benchmark",
        "",
        "Hosted measurements are a reproducible software-pack surrogate. Hardware-sensitive conclusions still require a real-hardware gate.",
        "",
        "| Variant | Runs | main_menu ms | mod_entrypoint ms | post-mod ms | MCEF ms | reload→FancyMenu ms | panorama ms | Decocraft status | remapped | sprites removed | blocks atlas | mixin errors |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: |",
    ]
    for variant in variants:
        data = summary[variant]
        markdown.append(
            f"| {variant} | {data['runs']} | {format_ms(data['startup_total_ms'])} | "
            f"{format_ms(data['mod_entrypoint_ms'])} | {format_ms(data['post_mod_entrypoint_ms'])} | "
            f"{format_ms(data['mcef_init_ms'])} | {format_ms(data['reload_to_fancymenu_finish_ms'])} | "
            f"{format_ms(data['fancymenu_panorama_ms'])} | {data['decocraft_status']} | "
            f"{data['decocraft_models_remapped'] if data['decocraft_models_remapped'] is not None else 'n/a'} | "
            f"{data['decocraft_atlas_removed'] if data['decocraft_atlas_removed'] is not None else 'n/a'} | "
            f"{data['blocks_atlas']} | {data['bootoptim_mixin_errors']} |"
        )

    if "candidate" in summary and "control" in summary:
        markdown.extend(["", "## Candidate minus control median deltas", ""])
        for metric in metrics:
            candidate = summary["candidate"].get(metric)
            control = summary["control"].get(metric)
            if candidate is not None and control is not None:
                delta = candidate - control
                pct = (delta / control * 100.0) if control else None
                pct_text = f" ({pct:+.2f}%)" if pct is not None else ""
                markdown.append(f"- `{metric}`: {delta:+,.1f} ms{pct_text}")

    markdown_text = "\n".join(markdown) + "\n"
    Path(args.output).write_text(markdown_text, encoding="utf-8")
    Path(args.json_output).write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")
    print(markdown_text)


def main():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    single = subparsers.add_parser("single")
    single.add_argument("--latest", required=True)
    single.add_argument("--startup", required=True)
    single.add_argument("--variant", required=True)
    single.add_argument("--iteration", type=int, required=True)
    single.add_argument("--output", required=True)
    single.set_defaults(func=parse_single)

    aggregate = subparsers.add_parser("aggregate")
    aggregate.add_argument("--results-dir", required=True)
    aggregate.add_argument("--output", required=True)
    aggregate.add_argument("--json-output", required=True)
    aggregate.set_defaults(func=parse_aggregate)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

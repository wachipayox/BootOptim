#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

PACK_TOKEN = "file/Glowing Trim Armors v5.0.zip"
LEGACY_LOG = "Using legacy nbt.display.Name"
PACK_FALLBACK_LOG = "Caught error loading resourcepacks, removing all selected resourcepacks"
EXPECTED_RULES = 7920
EXPECTED_ATLAS = (8192, 8192, 2)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", required=True, choices=("control", "candidate"))
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--latest", required=True, type=Path)
    parser.add_argument("--console", required=True, type=Path)
    args = parser.parse_args()

    report = json.loads(args.report.read_text(encoding="utf-8"))
    result = json.loads(args.result.read_text(encoding="utf-8"))
    latest = args.latest.read_text(encoding="utf-8", errors="replace")
    console = args.console.read_text(encoding="utf-8", errors="replace")

    if report.get("mode") != args.mode:
        raise SystemExit(f"CIT resource-pack report mode mismatch: report={report.get('mode')} expected={args.mode}")
    source = report.get("source", {})
    output = report.get("output", {})
    invariants = report.get("invariants", {})
    if source.get("legacy_keys") != EXPECTED_RULES or source.get("legacy_files") != EXPECTED_RULES:
        raise SystemExit(f"Unexpected source rule counts: {source}")
    required_invariants = (
        "rhs_bytes_preserved",
        "entry_order_preserved",
        "entry_metadata_preserved",
        "unchanged_payloads_identical",
        "candidate_payloads_reverse_exact",
        "archive_comment_preserved",
    )
    missing = [key for key in required_invariants if invariants.get(key) is not True]
    if missing:
        raise SystemExit(f"CIT resource-pack integrity invariant failed: {missing}")

    if PACK_FALLBACK_LOG in latest or PACK_FALLBACK_LOG in console:
        raise SystemExit("Minecraft removed the selected resource packs after a reload failure; run is not exact-pack interpretable")

    reload_lines = [line for line in latest.splitlines() if "Reloading ResourceManager:" in line]
    if not reload_lines:
        raise SystemExit("No Reloading ResourceManager line was captured")
    if PACK_TOKEN not in reload_lines[-1]:
        raise SystemExit(
            "Target pack is absent from the final Reloading ResourceManager state: "
            f"target={PACK_TOKEN} final={reload_lines[-1]}"
        )

    reload_wall = result.get("reload_to_fancymenu_finish_ms")
    panorama = result.get("fancymenu_panorama_ms")
    if not isinstance(reload_wall, (int, float)) or reload_wall <= 0:
        raise SystemExit(f"Missing/invalid reload→FancyMenu wall time: {reload_wall!r}")
    if not isinstance(panorama, (int, float)) or panorama <= 0:
        raise SystemExit(f"Missing/invalid FancyMenu panorama timing: {panorama!r}")

    atlas = (
        result.get("blocks_atlas_width"),
        result.get("blocks_atlas_height"),
        result.get("blocks_atlas_levels"),
    )
    if atlas != EXPECTED_ATLAS:
        raise SystemExit(f"Exact-pack blocks atlas invariant changed: actual={atlas} expected={EXPECTED_ATLAS}")
    if result.get("bootoptim_mixin_errors") != 0:
        raise SystemExit(f"BootOptim Mixin errors present: {result.get('bootoptim_mixin_errors')}")
    if not isinstance(result.get("main_menu_ms"), (int, float)) or result["main_menu_ms"] <= 0:
        raise SystemExit(f"Missing/invalid main-menu timing: {result.get('main_menu_ms')!r}")

    latest_legacy = latest.count(LEGACY_LOG)
    console_legacy = console.count(LEGACY_LOG)
    if args.mode == "candidate":
        expected = {
            "changed_entries": EXPECTED_RULES,
            "legacy_keys": 0,
            "legacy_files": 0,
            "modern_keys": EXPECTED_RULES,
            "modern_files": EXPECTED_RULES,
        }
        if latest_legacy != 0 or console_legacy != 0:
            raise SystemExit(
                f"Candidate used CITResewn legacy fallback: latest={latest_legacy} console={console_legacy}"
            )
        if 'Unknown component type "minecraft:custom_name"' in latest:
            raise SystemExit("Candidate failed to resolve minecraft:custom_name")
    else:
        expected = {
            "changed_entries": 0,
            "legacy_keys": EXPECTED_RULES,
            "legacy_files": EXPECTED_RULES,
            "modern_keys": 0,
            "modern_files": 0,
        }
        if latest_legacy != EXPECTED_RULES or console_legacy != EXPECTED_RULES:
            raise SystemExit(
                f"Control legacy fallback count mismatch: latest={latest_legacy} console={console_legacy} expected={EXPECTED_RULES}"
            )

    mismatches = {key: (value, output.get(key)) for key, value in expected.items() if output.get(key) != value}
    if mismatches:
        raise SystemExit(f"CIT resource-pack variant postcondition mismatch: {mismatches}")

    print(
        "BOOTOPTIM_CIT_RESOURCE_MIGRATION_GATE "
        f"mode={args.mode} pack_present_final=true packs_not_removed=true rules={EXPECTED_RULES} "
        f"legacy_latest={latest_legacy} legacy_console={console_legacy} "
        f"reload_to_fancymenu_ms={reload_wall} atlas={atlas[0]}x{atlas[1]}x{atlas[2]} "
        f"output_sha256={output.get('sha256')} reverse_exact=true"
    )


if __name__ == "__main__":
    main()

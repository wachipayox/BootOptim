"""Offline coarse phase comparison; log milestones are not profiler boundaries."""
import argparse
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts/exact-pack'))
from summarize_startup import delta_ms, find_time, parse_startup_report


def analyze(directory):
    lines = (directory / 'latest.log').read_text(encoding='utf-8-sig', errors='replace').splitlines()
    phases, total = parse_startup_report(directory / 'bootoptim-startup.log')
    entry = phases.get('mod_entrypoint')
    entry_clock = find_time(lines, 'BOOTOPTIM_STARTUP phase=mod_entrypoint')
    reload_clock = find_time(lines, 'Reloading ResourceManager:')
    atlas_clock = find_time(lines, 'minecraft:textures/atlas/blocks.png-atlas')
    fancy_end_clock = find_time(lines, 'Minecraft resource reload: FINISHED')
    preload_end_clock = find_time(lines, 'BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD')
    preload_ms = None
    for line in lines:
        if 'BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD' in line:
            match = re.search(r'\bpreload_ms=([0-9.]+)', line)
            if match:
                preload_ms = float(match.group(1))
            break
    preload_start_clock = None if preload_ms is None or preload_end_clock is None else preload_end_clock - preload_ms
    before_reload = delta_ms(entry_clock, reload_clock)
    title = phases.get('main_menu', total)
    after_fancy = None
    if entry is not None and title is not None and entry_clock is not None and fancy_end_clock is not None:
        after_fancy = title - entry - delta_ms(entry_clock, fancy_end_clock)
    segments = {
        'jvm_to_mod_entrypoint_ms': entry,
        'mod_entrypoint_to_reload_ms': before_reload,
        'reload_to_blocks_atlas_log_ms': delta_ms(reload_clock, atlas_clock),
        'blocks_atlas_log_to_preload_ms': delta_ms(atlas_clock, preload_start_clock),
        'fancymenu_preload_all_ms': preload_ms,
        'preload_to_fancymenu_finished_ms': delta_ms(preload_end_clock, fancy_end_clock),
        'fancymenu_finished_to_title_ms': after_fancy,
    }
    segments = {key: None if value is None else round(value, 3) for key, value in segments.items()}
    reload_count = sum('Reloading ResourceManager:' in line for line in lines)
    issues = []
    if reload_count != 1:
        issues.append('Expected one reload; first-milestone partition is ambiguous otherwise')
    if any(value is None for value in segments.values()):
        issues.append('Missing milestone; incomplete partition')
    if title is None or any(value is not None and (value < 0 or (title is not None and value > title))
                            for value in segments.values()):
        issues.append('Invalid elapsed interval or missing title; check clock continuity')
    return {
        'run': directory.name, 'title_ms': title, 'segments': segments,
        'segment_sum_ms': None if any(v is None for v in segments.values()) else round(sum(segments.values()), 3),
        'partition_issues': issues,
        'legacy_cit_warnings': sum('[citresewn] Using legacy nbt.display.Name' in line for line in lines),
        'reload_count': reload_count,
        'resource_fallback': any('Caught error loading resourcepacks' in line for line in lines),
        'scope': 'Coarse serial wall partitions using log timestamps and preload timer; not CPU or listener attribution. Validate resource contract and run identity separately.',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', nargs='+', type=Path)
    args = parser.parse_args()
    print(json.dumps([analyze(path) for path in args.directories], indent=2))


if __name__ == '__main__':
    main()

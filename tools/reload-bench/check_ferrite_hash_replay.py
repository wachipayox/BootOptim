"""Strict evidence reader; actual method replay is not reload critical-path timing."""
import argparse
import json
import re
from pathlib import Path


def check(text):
    issues = []
    if 'BOOTOPTIM_FERRITE_HASH_REPLAY stage=invalid' in text:
        issues.append('runtime declared invalid')
    if text.count('BOOTOPTIM_FERRITE_HASH_REPLAY stage=observing_initial') != 1:
        issues.append('need one client initial reload observer')
    if text.count('BOOTOPTIM_FERRITE_HASH_REPLAY stage=initial_complete success=true') != 1:
        issues.append('need one successful client initial reload completion')
    corpus = re.findall(r'BOOTOPTIM_FERRITE_HASH_REPLAY stage=corpus seen=(\d+) samples=(\d+) semantic_equal=true', text)
    if len(corpus) != 1 or int(corpus[0][1]) < 256:
        issues.append('need one sufficiently populated actual corpus')
    pattern = (r'BOOTOPTIM_FERRITE_HASH_REPLAY stage=observation index=(\d+) candidate=(true|false) '
               r'calls=(\d+) cpu_ns=(\d+) wall_ns=(\d+) checksum=(-?\d+) gc_total_ms=(\d+)')
    rows = [dict(index=int(i), candidate=c == 'true', calls=int(n), cpu_ns=int(cpu),
                 wall_ns=int(wall), checksum=int(s), gc_total_ms=int(gc))
            for i, c, n, cpu, wall, s, gc in re.findall(pattern, text)]
    if [r['index'] for r in rows] != [0, 1, 2, 3]:
        issues.append('need exactly four ordered observations')
    if [r['candidate'] for r in rows] != [False, True, True, False]:
        issues.append('need C/B/B/C')
    if rows and (len({r['calls'] for r in rows}) != 1 or rows[0]['calls'] < 7_990_000
                 or len({r['checksum'] for r in rows}) != 1):
        issues.append('inconsistent workload/checksum')
    if any(r['cpu_ns'] <= 0 or r['wall_ns'] <= 0 for r in rows):
        issues.append('invalid block clocks')
    if text.count('BOOTOPTIM_FERRITE_HASH_REPLAY stage=finished controls=2 candidates=2') != 1:
        issues.append('need one successful finished marker')
    if 'BOOTOPTIM_FERRITE_STRIPED_HASH status=active version=7.0.3 verify=false' not in text:
        issues.append('missing expected actual target activation')
    if 'BOOTOPTIM_FERRITE_STRIPED_HASH_VERIFY checks=' in text:
        issues.append('doubled-work verification is not performance')
    if 'BOOTOPTIM_STARTUP phase=main_menu' not in text:
        issues.append('missing main menu')
    contrasts = []
    if len(rows) == 4:
        for control, candidate in [(rows[0], rows[1]), (rows[3], rows[2])]:
            contrasts.append({key + '_saved_ms': (control[key + '_ns'] - candidate[key + '_ns']) / 1e6
                              for key in ('cpu', 'wall')})
    return dict(valid=not issues, issues=issues, origin='hosted actual transformed hash replay',
                endpoint='synchronous method block return', observations=rows,
                contrasts=contrasts, not_measured='reload critical path or whole deduplication')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('log', type=Path)
    args = parser.parse_args()
    result = check(args.log.read_text(encoding='utf-8', errors='replace'))
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['valid'] else 1)

"""Owner-only actual transformed Sodium classifier, real pack quads, four warmed blocks."""
import argparse
import json
from pathlib import Path
import re


def check(text):
    events = [dict(re.findall(r'(\w+)=([^\s]+)', line.split('BOOTOPTIM_SODIUM_REPLAY ', 1)[1]))
              for line in text.splitlines() if 'BOOTOPTIM_SODIUM_REPLAY ' in line]
    issues, rows = [], []
    if [e.get('stage') for e in events] != ['initial_complete', 'corpus'] + ['observation'] * 4 + ['finished']:
        return {'valid': False, 'issues': ['Missing/duplicate/reordered replay boundaries']}
    if events[0].get('success') != 'true' or events[1].get('semantic_equal') != 'true':
        issues.append('Invalid source generation or flags equivalence')
    if events[1].get('origin') != 'method_block' or events[1].get('endpoint') != 'method_block':
        issues.append('Invalid owner boundary')
    if any(events[-1].get(k) != '2' for k in ('controls', 'candidates')):
        issues.append('Incorrect arm count')
    try:
        samples = int(events[1].get('samples', '-1'))
        if not 1024 <= samples <= 4096 or int(events[1].get('eligible', '-1')) < samples:
            issues.append('Invalid actual corpus')
        for index, event in enumerate(events[2:6]):
            if int(event.get('index', '-1')) != index or int(event.get('mask', '-1')) != (0, 4, 4, 0)[index]:
                issues.append('Wrong arm/order')
            row = {k: int(event.get(k, '-1')) for k in ('calls', 'cpu_ns', 'wall_ns', 'checksum', 'gc_total_ms')}
            if any(v < 0 for v in row.values()) or row['cpu_ns'] == 0 or row['calls'] != samples * (8_000_000 // samples):
                issues.append('Invalid block workload/clock')
            rows.append(row)
    except ValueError:
        issues.append('Malformed numerical marker')
    if len({r['checksum'] for r in rows}) != 1 or len({r['calls'] for r in rows}) != 1:
        issues.append('Flags or actual call count changed')
    if text.count('Reloading ResourceManager:') != 1 or text.count('BOOTOPTIM_SODIUM_AXIS_QUAD_FLAGS status=active') != 1:
        issues.append('Missing exact activation or extra resource generation')
    if any(f in text for f in ('InvalidInjectionException', 'Caught error loading resourcepacks', 'Mixin apply for mod boot_optim failed')):
        issues.append('Runtime failure')
    if issues:
        return {'valid': False, 'issues': issues, 'observations': rows}
    pairs = [(rows[0], rows[1]), (rows[3], rows[2])]
    deltas = [{k.replace('_ns', '_ms'): (b[k]-a[k]) / (1e6 if k.endswith('_ns') else 1)
               for k in ('cpu_ns', 'wall_ns', 'gc_total_ms')} for a, b in pairs]
    # Clock/read/write overhead and warmup are equal; warmed method block only, not reload wall.
    saving = all(d['cpu_ms'] < 0 for d in deltas)
    return {'valid': True, 'issues': [], 'observations': rows, 'candidate_minus_control': deltas,
            'segment_verdict': 'retain_segment_mechanism' if saving else 'retire_segment_mechanism',
            'scope': 'Actual transformed getQuadFlags and candidate guard, identical real-pack corpus replay; excludes initial capture, other bake owners and real reload scheduling'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('log', type=Path)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    result = check(a.log.read_text(encoding='utf-8-sig', errors='replace'))
    a.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['valid'] else 1)

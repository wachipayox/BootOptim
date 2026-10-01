"""Two-control/two-candidate Ferrite owner-first evidence; no listener sums."""
import argparse
import json
from pathlib import Path
import re
import statistics

MASKS = (0, 0, 2, 2, 2, 2, 0, 0)


def check(text):
    events = [dict(re.findall(r'(\w+)=([^\s]+)', line.split('BOOTOPTIM_FERRITE_PHASE ', 1)[1]))
              for line in text.splitlines() if 'BOOTOPTIM_FERRITE_PHASE ' in line]
    expected_stages = ['initial_complete', 'initial_menu'] + ['requested', 'complete'] * 8 + ['finished']
    issues, rows = [], []
    if [e.get('stage') for e in events] != expected_stages:
        return {'valid': False, 'issues': ['Missing, duplicated or reordered phase boundaries']}
    if events[0].get('success') != 'true' or events[1].get('origin') != 'jvm_uptime':
        issues.append('Invalid initial generation')
    if any(events[-1].get(k) != v for k, v in dict(observations='4', controls='2', candidates='2', primers='4').items()):
        issues.append('Invalid endpoint')
    keys = ('growth_cpu_ns', 'growth_wall_ns', 'growth_calls', 'clear_cpu_ns', 'clear_wall_ns',
            'trim_cpu_ns', 'trim_wall_ns', 'previous_unique', 'empty_slots', 'reload_wall_ns', 'gc_ms', 'heap_used_bytes')
    try:
        if int(events[1].get('uptime_ms', '-1')) <= 0:
            issues.append('Invalid startup origin')
        for index, mask in enumerate(MASKS):
            req, done = events[2 + index * 2:4 + index * 2]
            measured = index % 2 == 1
            if any(e.get(k) != v for e in (req, done)
                   for k, v in dict(index=str(index), mask=str(mask), measured=str(measured).lower()).items()):
                issues.append(f'Invalid trial identity {index}')
            if done.get('success') != 'true':
                issues.append(f'Failed trial {index}')
            row = {k: int(done.get(k, '-1')) for k in keys}
            row.update(index=index, candidate=mask != 0)
            if any(row[k] < 0 for k in keys) or row['previous_unique'] <= 0 or row['empty_slots'] <= 0:
                issues.append(f'Invalid owner data {index}')
            if mask == 0 and row['empty_slots'] != 1 or mask != 0 and not 1 < row['empty_slots'] <= 2**21:
                issues.append(f'Capacity mode not applied {index}')
            if measured:
                row['owner_cpu_ns'] = sum(row[k] for k in ('growth_cpu_ns', 'clear_cpu_ns', 'trim_cpu_ns'))
                row['owner_wall_ns'] = sum(row[k] for k in ('growth_wall_ns', 'clear_wall_ns', 'trim_wall_ns'))
                rows.append(row)
    except (ValueError, TypeError) as exc:
        issues.append(f'Invalid numerical marker: {exc}')
    if text.count('Reloading ResourceManager:') != 9:
        issues.append('Expected exactly nine full resource generations')
    for failure in ('Caught error loading resourcepacks', 'InvalidInjectionException', 'Mixin apply for mod boot_optim failed',
                    'BOOTOPTIM_FERRITE_QUAD_CAPACITY previous_unique=0', 'retained_model_data=true'):
        if failure in text:
            issues.append(failure)
    capacity = [line for line in text.splitlines() if 'BOOTOPTIM_FERRITE_QUAD_CAPACITY ' in line]
    if len(capacity) != 4 or any('success=true' not in line or 'retained_model_data=false' not in line for line in capacity):
        issues.append('Expected four successful candidate capacity transitions')
    if issues:
        return {'valid': False, 'issues': issues, 'observations': rows}
    # C1,B1 and B2,C2: two opposite-order contrasts in the same JVM.
    pairs = [(rows[0], rows[1]), (rows[3], rows[2])]
    deltas = [{(k[:-3] + '_ms' if k.endswith('_ns') else k): (b[k] - a[k]) / (1e6 if k.endswith('_ns') else 1)
               for k in ('owner_cpu_ns', 'growth_cpu_ns', 'owner_wall_ns', 'growth_calls', 'reload_wall_ns', 'gc_ms', 'heap_used_bytes')}
              for a, b in pairs]
    work_spread = (max(r['previous_unique'] for r in rows) / min(r['previous_unique'] for r in rows)) - 1
    if work_spread > .01:
        return {'valid': False, 'issues': ['Unique quad workload differs by more than 1%'], 'observations': rows}
    saved = all(d['owner_cpu_ms'] < 0 for d in deltas)
    fewer = all(d['growth_calls'] < 0 for d in deltas)
    # Phase result is explicit. Whole-reload and GC are adjacent evidence, not an unrelated noise veto.
    return {'valid': True, 'issues': [], 'observations': rows,
            'candidate_minus_control': deltas,
            'median_owner_cpu_saved_ms': -statistics.median(d['owner_cpu_ms'] for d in deltas),
            'segment_verdict': 'retain_segment_mechanism' if saved and fewer else 'retire_segment_mechanism',
            'scope': 'Actual nonoverlapping rehash + clear + trim intervals, one JVM, two controls/two candidates; final promotion also requires semantic and attributable heap/GC checks'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('log', type=Path)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    result = check(a.log.read_text(encoding='utf-8-sig', errors='replace'))
    a.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['valid'] else 1)

"""Actual per-owner calls, not whole bake/load clocks. Two measured controls/two candidates each."""
import argparse
import json
from pathlib import Path
import re

OWNERS = ('decocraft', 'layer', 'multipart')
BITS = (1, 8, 16)


def check(text):
    events = [dict(re.findall(r'(\w+)=([^\s]+)', line.split('BOOTOPTIM_OWNER_TRIAL ', 1)[1]))
              for line in text.splitlines() if 'BOOTOPTIM_OWNER_TRIAL ' in line]
    stages = ['initial_complete', 'initial_menu'] + ['requested', 'owner', 'owner', 'owner', 'complete'] * 24 + ['finished']
    issues, observations = [], {owner: [] for owner in OWNERS}
    if [e.get('stage') for e in events] != stages:
        return {'valid': False, 'issues': ['Missing/duplicate/reordered owner lifecycle']}
    if events[0].get('success') != 'true' or events[1].get('origin') != 'jvm_uptime':
        issues.append('Invalid initial boundary')
    if any(events[-1].get(k) != str(v) for k, v in {'observations': 12, 'controls': 6, 'candidates': 6, 'primers': 12}.items()):
        issues.append('Wrong measured/primer count')
    try:
        for index in range(24):
            requested, *rows, complete = events[2 + 5 * index:7 + 5 * index]
            owner = OWNERS[index // 8]
            mask = BITS[index // 8] * (0, 1, 1, 0)[(index // 2) % 4]
            measured = str(bool(index % 2)).lower()
            for boundary in (requested, complete):
                if (int(boundary.get('index', -1)), boundary.get('owner'), int(boundary.get('mask', -1)), boundary.get('measured')) != (index, owner, mask, measured):
                    issues.append('Wrong index/owner/mask/primer')
            if complete.get('success') != 'true':
                issues.append('Failed resource generation')
            parsed = {}
            for expected, row in zip(OWNERS, rows):
                if row.get('owner') != expected or row.get('index') != str(index) or row.get('valid') != 'true':
                    issues.append('Invalid or unmatched owner scope')
                values = {k: int(row.get(k, -1)) for k in ('calls', 'cpu_ns', 'wall_ns', 'work', 'matching')}
                if values['calls'] <= 0 or values['cpu_ns'] <= 0 or any(v < 0 for v in values.values()):
                    issues.append('Invalid workload/CPU clock')
                parsed[expected] = values
            if index % 2:
                adjacent = {k: int(complete.get(k, -1)) for k in ('reload_wall_ns', 'gc_ms', 'heap_used_bytes')}
                if any(v < 0 for v in adjacent.values()): issues.append('Invalid adjacent boundary')
                observations[owner].append({**parsed[owner], **adjacent})
        if text.count('Reloading ResourceManager:') != 25:
            issues.append('Wrong number of resource generations')
        if text.count('mode=substitute_v2 reason=version_3.0.11') != 4:
            issues.append('Missing Decocraft actual candidate activation')
        if text.count('BOOTOPTIM_MULTIPART_UNION success=true enabled=true') != 4:
            issues.append('Missing multipart actual candidate activation')
    except ValueError:
        issues.append('Malformed number')
    if any(s in text for s in ('InvalidInjectionException', 'Caught error loading resourcepacks', 'Mixin apply for mod boot_optim failed', 'Direct generated-item bake failed open')):
        issues.append('Runtime failure/fallback')
    verdicts = {}
    for owner, rows in observations.items():
        if len(rows) != 4:
            issues.append('Incorrect owner observation count'); continue
        for key in ('calls', 'work', 'matching'):
            values = [r[key] for r in rows]
            if max(values) - min(values) > max(1, max(values) * .01):
                issues.append('Changed owner workload')
        deltas = [{k.replace('_ns', '_ms'): (b[k] - a[k]) / (1e6 if k.endswith('_ns') else 1)
                   for k in ('cpu_ns', 'wall_ns', 'reload_wall_ns', 'gc_ms', 'heap_used_bytes')}
                  for a, b in ((rows[0], rows[1]), (rows[3], rows[2]))]
        eligible = owner != 'layer' or all(r['matching'] > 0 for r in rows)
        verdicts[owner] = {'observations': rows, 'candidate_minus_control': deltas,
                          'segment_verdict': 'retain_segment_mechanism' if eligible and all(d['cpu_ms'] < 0 for d in deltas) else 'retire_segment_mechanism'}
    return {'valid': not issues, 'issues': issues, 'owners': verdicts,
            'scope': 'Per-owner CPU includes unchanged body/callbacks within that method; elapsed call-sums are not reload critical wall. Adjacent reload/GC are separate, not all attributable.'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('log', type=Path); p.add_argument('--output', type=Path, required=True)
    a = p.parse_args(); result = check(a.log.read_text(encoding='utf-8-sig', errors='replace'))
    a.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2)); raise SystemExit(0 if result['valid'] else 1)

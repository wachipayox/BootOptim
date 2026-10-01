"""Offline, strict validation of the fixed within-JVM candidate experiment."""
import argparse
import json
from pathlib import Path
import re
import statistics


def plan():
    steps = [('warmup', 0, 0, False, 'warmup')]
    for feature, bit, reverse, prime in [('decocraft', 1, False, False), ('sodium', 4, True, False), ('layer', 8, False, False), ('ferrite', 2, True, True)]:
        for i, mask in enumerate([bit, 0, 0, bit] if reverse else [0, bit, bit, 0]):
            if prime:
                steps.append((feature, mask, i // 2 + 1, False, 'conditioning'))
            steps.append((feature, mask, i // 2 + 1, True, 'measurement'))
    return steps


def check(text):
    events = [dict(re.findall(r'(\w+)=([^\s]+)', line.split('BOOTOPTIM_TRIAL ', 1)[1])) for line in text.splitlines() if 'BOOTOPTIM_TRIAL ' in line]
    expected = plan()
    stages = ['initial_menu'] + [s for _ in expected for s in ('requested', 'complete')] + ['finished']
    issues = []
    if [e.get('stage') for e in events] != stages:
        return {'valid': False, 'issues': ['Missing, duplicated or reordered trial markers'], 'features': {}}
    if events[0].get('origin') != 'jvm_uptime' or int(events[0].get('uptime_ms', '-1')) <= 0:
        issues.append('Invalid initial origin')
    if any(events[-1].get(k) != v for k, v in {'steps': '21', 'measured': '16', 'origin': 'reload_invocation', 'endpoint': 'future_completion'}.items()):
        issues.append('Invalid terminal endpoint')
    samples = []
    for i, (feature, mask, pair, measured, kind) in enumerate(expected):
        request, done = events[1 + i * 2:3 + i * 2]
        fields = {'index': str(i), 'feature': feature, 'mask': str(mask), 'pair': str(pair), 'measured': str(measured).lower()}
        if any(e.get(k) != v for e in (request, done) for k, v in fields.items()) or request.get('kind') != kind or done.get('success') != 'true':
            issues.append(f'Invalid generation {i}')
        row = dict(feature=feature, mask=mask, pair=pair)
        for name in ('reload_wall_ns', 'bake_wall_ns', 'bake_cpu_ns', 'gc_ms', 'heap_used_bytes'):
            row[name] = int(done.get(name, '-1'))
            if row[name] < 0:
                issues.append(f'Invalid {name} at {i}')
        if measured:
            samples.append(row)
    if text.count('Reloading ResourceManager:') != 22:
        issues.append('Expected precisely 22 resource generations')
    for marker in ('Caught error loading resourcepacks', 'Direct generated-item bake failed open', 'BOOTOPTIM_SWEEP stage=invalid', 'InvalidInjectionException', 'Mixin apply for mod boot_optim failed'):
        if marker in text:
            issues.append(marker)
    features = {}
    for feature in ('decocraft', 'sodium', 'layer', 'ferrite'):
        rows = [r for r in samples if r['feature'] == feature]
        pairs = []
        for pair in (1, 2):
            control = next(r for r in rows if r['pair'] == pair and r['mask'] == 0)
            candidate = next(r for r in rows if r['pair'] == pair and r['mask'] != 0)
            pairs.append({k: (candidate[k] - control[k]) / (1e6 if k.endswith('_ns') else 1) for k in ('reload_wall_ns', 'bake_wall_ns', 'bake_cpu_ns', 'gc_ms')})
        cpu_threshold = max(200, statistics.median(r['bake_cpu_ns'] / 1e6 for r in rows if r['mask'] == 0) * .01)
        wall_threshold = max(1000, statistics.median(r['reload_wall_ns'] / 1e6 for r in rows if r['mask'] == 0) * .02)
        cpu_win = all(p['bake_cpu_ns'] < 0 for p in pairs) and -statistics.median(p['bake_cpu_ns'] for p in pairs) >= cpu_threshold
        regression = all(p['reload_wall_ns'] > wall_threshold for p in pairs)
        features[feature] = {'pairs_candidate_minus_control_ms': pairs, 'cpu_threshold_ms': cpu_threshold, 'wall_regression_threshold_ms': wall_threshold, 'decision': 'reject_wall_regression' if regression else 'candidate_for_final_uninstrumented_gate' if cpu_win else 'no_demonstrated_practical_win', 'samples': rows}
    return {'valid': not issues, 'issues': issues, 'features': features, 'scope': 'Within-JVM reload CPU evidence; not proof of cold startup improvement'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = check(args.log.read_text(encoding='utf-8-sig', errors='replace'))
    except (OSError, ValueError, StopIteration) as error:
        result = {'valid': False, 'issues': [str(error)]}
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['valid'] else 1)

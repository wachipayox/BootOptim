"""Offline gate for the finite menu-only JSON-owner trial, read only after exit."""
import argparse
import json
from pathlib import Path
import re

def fields(line):
    return dict(re.findall(r'(\w+)=([^\s]+)', line))

def check_semantic(text):
    owners = [fields(line) for line in text.splitlines() if 'BOOTOPTIM_JSON_OWNER ' in line]
    batches = [fields(line) for line in text.splitlines() if 'BOOTOPTIM_DECOCRAFT_MODEL_BATCH status=complete ' in line]
    prerequisites = [fields(line) for line in text.splitlines() if 'BOOTOPTIM_JSON_PREREQUISITE ' in line]
    if len(owners) != 1 or len(batches) != 1 or len(prerequisites) != 2:
        raise ValueError('semantic generation/endpoint cardinality')
    owner, batch = owners[0], batches[0]
    if not (owner['generation'] == batch['generation'] == '1' and owner['success'] == batch['success'] == 'true'
            and owner['candidate'] == owner['cpu_valid'] == 'true'
            and int(owner['tasks']) == int(owner['opens']) == int(batch['hits']) == int(batch['verified']) == 10809
            and int(batch['fallbacks']) == 0 and int(batch['retained_bytes']) == 3129313
            and int(owner['batches']) == 1):
        raise ValueError('semantic verification did not cover exact corpus')
    if {item['phase'] for item in prerequisites} != {'models', 'states'} or not all(
            item['generation'] == '1' and item['success'] == 'true' for item in prerequisites):
        raise ValueError('semantic prerequisite failure')
    if 'resource_byte_mismatch' in text or 'archive_validation_failed' in text:
        raise ValueError('semantic corpus mismatch')
    return {'valid': True, 'semantic_only': True, 'verified': 10809, 'performance_evidence': False}

def check(text):
    def records(marker):
        return [fields(line) for line in text.splitlines() if marker in line]
    def require(condition, reason):
        if not condition:
            raise ValueError(reason)
    require('BOOTOPTIM_JSON_TRIAL stage=invalid' not in text, 'invalid trial')
    require(not any(item in text for item in ('InvalidInjectionException', 'Mixin apply for mod boot_optim failed',
                                             'resource reload failed', 'Failed to reload resources')), 'runtime error')
    requested = records('BOOTOPTIM_JSON_TRIAL stage=requested')
    completed = records('BOOTOPTIM_JSON_TRIAL stage=complete ')
    owners = records('BOOTOPTIM_JSON_OWNER ')
    initial = records('BOOTOPTIM_JSON_TRIAL stage=initial_complete')
    menu = records('BOOTOPTIM_JSON_TRIAL stage=initial_menu')
    finish = records('BOOTOPTIM_JSON_TRIAL stage=finished')
    prerequisites = records('BOOTOPTIM_JSON_PREREQUISITE ')
    require(len(initial) == len(menu) == len(finish) == 1, 'initial/menu/finish cardinality')
    require(initial[0]['success'] == 'true' and menu[0]['origin'] == 'jvm_uptime', 'initial origin/endpoint')
    require(len(requested) == len(completed) == 8 and len(owners) == 9, 'expected nine generations/eight reloads')
    require(len(prerequisites) == 18, 'missing prerequisite endpoints')
    for generation in range(1, 10):
        phases = [item for item in prerequisites if int(item['generation']) == generation]
        require(len(phases) == 2 and {item['phase'] for item in phases} == {'models', 'states'}, 'duplicate/missing prerequisite')
        require(all(item['success'] == 'true' and int(item['wall_ns']) > 0 for item in phases), 'failed prerequisite')
    require(finish[0].get('observations') == '4' and finish[0].get('primers') == '4', 'trial shape')
    for n, owner in enumerate(owners, 1):
        require(int(owner['generation']) == n and owner['success'] == owner['cpu_valid'] == 'true', 'owner validity')
        require(int(owner['tasks']) == int(owner['opens']) == 10809, 'owner workload changed')
        require(int(owner['batches']) == (1 if n == 4 else 0), 'unexpected corpus refill')
    require(owners[0]['candidate'] == 'false', 'initial generation must be stock')
    for n, (request, result) in enumerate(zip(requested, completed)):
        require(int(request['index']) == int(result['index']) == n, 'ordered unique index')
        candidate = 'true' if n in (2, 3, 4, 5) else 'false'
        measured = 'true' if n % 2 else 'false'
        require(request['candidate'] == result['candidate'] == candidate, 'mode order')
        require(request['measured'] == result['measured'] == measured, 'primer/observation order')
        require(result['success'] == 'true', 'failed reload')
        require(int(result['tasks']) == int(result['opens']) == 10809, 'trial workload')
        require(int(result['hits']) == (10809 if candidate == 'true' else 0), 'inactive candidate or leaked control')
        require(int(result['retained_bytes']) == (3129313 if candidate == 'true' else 0), 'storage lifetime')
        require(int(result['batches']) == (1 if n == 2 else 0), 'unexpected corpus refill')
        for key in ('task_cpu_ns', 'task_wall_ns', 'open_cpu_ns', 'open_wall_ns', 'batch_cpu_ns',
                    'batch_wall_ns', 'gc_ms', 'heap_used_bytes'):
            require(int(result[key]) >= 0, 'negative clock/counter')
        require(int(result['reload_wall_ns']) > 0, 'missing reload endpoint')
        require(int(result['open_cpu_ns']) <= int(result['task_cpu_ns']) and
                int(result['open_wall_ns']) <= int(result['task_wall_ns']), 'nested budgets do not reconstruct')
        owner = owners[n+1]
        require(owner['candidate'] == candidate, 'owner mode mismatch')
        for key in ('tasks', 'opens', 'task_cpu_ns', 'task_wall_ns', 'open_cpu_ns', 'open_wall_ns',
                    'batches', 'batch_cpu_ns', 'batch_wall_ns'):
            require(owner[key] == result[key], 'owner/trial mismatch: '+key)
    observations = [result for result in completed if result['measured'] == 'true']
    # Paired contrasts, rather than overlapping sums or medians across unlike phases.
    contrasts = []
    for control, candidate in ((observations[0], observations[1]), (observations[3], observations[2])):
        contrasts.append({key: int(candidate[key])-int(control[key]) for key in
                          ('task_cpu_ns', 'task_wall_ns', 'open_cpu_ns', 'open_wall_ns',
                           'reload_wall_ns', 'gc_ms', 'heap_used_bytes')})
    return {'valid': True, 'origin': 'stock reloadResourcePacks request to returned-future completion',
            'owner': 'current-thread CPU and task-wall sum of exact Decocraft read/parse/close tasks',
            'nested': 'open and corpus fill are nested; NEVER sum with task',
            'observations': observations, 'candidate_minus_control': contrasts}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('log', type=Path)
    parser.add_argument('--semantic', action='store_true')
    args = parser.parse_args()
    try:
        validator = check_semantic if args.semantic else check
        print(json.dumps(validator(args.log.read_text(encoding='utf-8', errors='replace')), indent=2))
    except (ValueError, KeyError) as error:
        print(json.dumps({'valid': False, 'reason': str(error)}))
        raise SystemExit(1)

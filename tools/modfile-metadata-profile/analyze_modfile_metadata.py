#!/usr/bin/env python3
import argparse
import json
from collections import defaultdict
from pathlib import Path


def ms(ns):
    return round(ns / 1_000_000.0, 3)


def parse_detail(text):
    out = {}
    if text:
        for part in text.split(';'):
            if '=' in part:
                key, value = part.split('=', 1)
                out[key] = value
    return out


def union_ns(events):
    intervals = sorted((e['start_ns'], e['end_ns']) for e in events if e['end_ns'] >= e['start_ns'])
    if not intervals:
        return 0
    total = 0
    start, end = intervals[0]
    for next_start, next_end in intervals[1:]:
        if next_start <= end:
            end = max(end, next_end)
        else:
            total += end - start
            start, end = next_start, next_end
    return total + end - start


def cpu_ns(events):
    values = [e['cpu_ns'] for e in events]
    return sum(values) if values and all(v >= 0 for v in values) else None


def contained(children, parents):
    return [c for c in children if any(
        c['tid'] == p['tid'] and p['start_ns'] <= c['start_ns'] and c['end_ns'] <= p['end_ns']
        for p in parents)]


def metric(events):
    cpu = cpu_ns(events)
    return {
        'calls': len(events),
        'union_wall_ms': ms(union_ns(events)),
        'sum_wall_ms': ms(sum(e['duration_ns'] for e in events)),
        'thread_cpu_ms': None if cpu is None else ms(cpu),
        'thread_count': len({e['tid'] for e in events}),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--trace', required=True)
    ap.add_argument('--json-output', required=True)
    ap.add_argument('--markdown-output', required=True)
    args = ap.parse_args()

    events = [json.loads(line) for line in Path(args.trace).read_text().splitlines() if line.strip()]
    headers = [e for e in events if e['kind'] == 'profile_header']
    if len(headers) != 1 or 'accepted=true' not in (headers[0].get('detail') or ''):
        raise SystemExit('profile did not accept exactly fml_loader@4.0.43')
    throws = [e for e in events if 'throw=' in (e.get('detail') or '')]
    if throws:
        raise SystemExit('instrumented stock boundary threw: ' + ', '.join(e['kind'] + ':' + e['detail'] for e in throws))

    by_kind = defaultdict(list)
    for event in events:
        by_kind[event['kind']].append(event)

    stage1 = by_kind['stage1_validation']
    reads = by_kind['read_mod_list']
    standard = by_kind['mods_toml_parser']
    loads = by_kind['file_config_load']
    copies = by_kind['immutable_copy_write_reparse']
    if len(stage1) != 1 or not reads or not standard or not loads or not copies:
        raise SystemExit('missing required FML metadata boundaries')

    for event in reads:
        event['_detail'] = parse_detail(event.get('detail'))
    initial_reads = [e for e in reads if e['_detail'].get('phase') != 'stage1']
    stage1_reads = [e for e in reads if e['_detail'].get('phase') == 'stage1']
    if not initial_reads or not stage1_reads:
        raise SystemExit('did not observe both initial and stage1 readModList routes')

    def is_standard(read):
        return any(s['tid'] == read['tid'] and read['start_ns'] <= s['start_ns'] and s['end_ns'] <= read['end_ns'] for s in standard)

    parser_groups = defaultdict(lambda: {
        'initial': [], 'stage1': [], 'identities_initial': set(), 'identities_stage1': set(), 'standard': False
    })
    for event in reads:
        detail = event['_detail']
        parser_type = detail.get('parser_class', 'unknown')
        phase = 'stage1' if detail.get('phase') == 'stage1' else 'initial'
        group = parser_groups[parser_type]
        group[phase].append(event)
        group['standard'] = group['standard'] or is_standard(event)
        try:
            group['identities_' + phase].add(int(detail.get('parser_identity', '0')))
        except ValueError:
            pass

    parser_summary = {}
    double_standard = False
    for parser_type, group in parser_groups.items():
        initial_ids = group['identities_initial']
        stage1_ids = group['identities_stage1']
        shared = initial_ids & stage1_ids
        if group['standard'] and group['initial'] and group['stage1'] and shared:
            double_standard = True
        parser_summary[parser_type] = {
            'classification': 'standard_neoforge_mods_toml' if group['standard'] else 'other',
            'initial': metric(group['initial']),
            'stage1': metric(group['stage1']),
            'parser_identity_count_initial': len(initial_ids),
            'parser_identity_count_stage1': len(stage1_ids),
            'shared_parser_identity_count': len(shared),
        }
    if not double_standard:
        raise SystemExit('standard neoforge.mods.toml parser was not observed in both routes with shared parser identity')

    stage1_standard = [s for s in standard if any(
        r['tid'] == s['tid'] and r['start_ns'] <= s['start_ns'] and s['end_ns'] <= r['end_ns'] for r in stage1_reads)]
    initial_standard = [s for s in standard if any(
        r['tid'] == s['tid'] and r['start_ns'] <= s['start_ns'] and s['end_ns'] <= r['end_ns'] for r in initial_reads)]
    stage1_loads = contained(loads, stage1_standard)
    stage1_copies = contained(copies, stage1_standard)
    initial_loads = contained(loads, initial_standard)
    initial_copies = contained(copies, initial_standard)
    if len(stage1_loads) != len(stage1_standard) or len(stage1_copies) != len(stage1_standard):
        raise SystemExit('stage1 standard parser did not contain exactly one FileConfig.load and one immutable-copy reparse per call')
    if len(initial_loads) != len(initial_standard) or len(initial_copies) != len(initial_standard):
        raise SystemExit('initial standard parser did not contain exactly one FileConfig.load and one immutable-copy reparse per call')

    coremods = by_kind['coremod_checks']
    mixins = by_kind['mixin_checks']
    access_transformers = by_kind['access_transformer_metadata_checks']
    if not coremods or not mixins or not access_transformers:
        raise SystemExit('stage1 coremod/mixin/access-transformer metadata checks were not all observed')

    stage1_wall = union_ns(stage1)
    stage1_named = union_ns(stage1_reads + coremods + mixins + access_transformers)
    standard_stage1_wall = union_ns(stage1_standard)
    standard_stage1_children = union_ns(stage1_loads + stage1_copies)
    standard_initial_wall = union_ns(initial_standard)
    standard_initial_children = union_ns(initial_loads + initial_copies)

    result = {
        'fml_version': headers[0]['module_version'],
        'double_standard_parser_route': True,
        'parser_types': parser_summary,
        'segments': {
            'stage1_validation': metric(stage1),
            'stage1_read_mod_list': metric(stage1_reads),
            'stage1_coremod_checks': metric(coremods),
            'stage1_mixin_checks': metric(mixins),
            'stage1_access_transformer_metadata_checks': metric(access_transformers),
            'stage1_residual_after_named_children_wall_ms': ms(max(0, stage1_wall - stage1_named)),
            'standard_initial_total': metric(initial_standard),
            'standard_initial_file_config_load': metric(initial_loads),
            'standard_initial_immutable_copy_write_reparse': metric(initial_copies),
            'standard_initial_parser_residual_wall_ms': ms(max(0, standard_initial_wall - standard_initial_children)),
            'standard_stage1_total': metric(stage1_standard),
            'standard_stage1_file_config_load': metric(stage1_loads),
            'standard_stage1_immutable_copy_write_reparse': metric(stage1_copies),
            'standard_stage1_parser_residual_wall_ms': ms(max(0, standard_stage1_wall - standard_stage1_children)),
        },
        'throws': [],
        'notes': [
            'Wall unions are non-overlapping interval unions; sum_wall_ms is reported only as task-sum context.',
            'The stage1 residual includes loop/bookkeeping and later per-path access-transformer Files.exists/notExists probes.',
            'The direct ModFile.identifyMods advice is not used as a required boundary because this exact launch shape did not transform that already-loaded class; stage1 readModList plus helper calls are instead bounded directly by ModValidator.stage1Validation.',
            'Parser identity is System.identityHashCode only; no parser or ModFile object is retained.',
        ],
    }
    Path(args.json_output).write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')

    segments = result['segments']
    lines = [
        '# Agent 137 ModFile metadata profile', '',
        f"- FML: `{result['fml_version']}`",
        '- standard `neoforge.mods.toml` parser observed in both initial construction and stage 1: **yes**',
        '', '## Wall union / thread CPU', '',
        '| scope | calls | union wall ms | thread CPU ms |', '|---|---:|---:|---:|'
    ]
    order = [
        ('stage1_validation', 'stage1Validation'),
        ('stage1_read_mod_list', 'stage1 readModList'),
        ('stage1_coremod_checks', 'coremod checks'),
        ('stage1_mixin_checks', 'mixin checks'),
        ('stage1_access_transformer_metadata_checks', 'AT metadata checks'),
        ('standard_initial_total', 'standard parser initial total'),
        ('standard_initial_file_config_load', 'initial FileConfig.load'),
        ('standard_initial_immutable_copy_write_reparse', 'initial immutable write→reparse'),
        ('standard_stage1_total', 'standard parser stage1 total'),
        ('standard_stage1_file_config_load', 'stage1 FileConfig.load'),
        ('standard_stage1_immutable_copy_write_reparse', 'stage1 immutable write→reparse'),
    ]
    for key, label in order:
        data = segments[key]
        cpu = 'n/a' if data['thread_cpu_ms'] is None else f"{data['thread_cpu_ms']:.3f}"
        lines.append(f"| {label} | {data['calls']} | {data['union_wall_ms']:.3f} | {cpu} |")
    lines += [
        '', '## Non-overlap residuals', '',
        f"- stage1 after read/coremod/mixin/AT-metadata children: **{segments['stage1_residual_after_named_children_wall_ms']:.3f} ms wall**",
        f"- standard initial parser outside FileConfig.load + immutable copy: **{segments['standard_initial_parser_residual_wall_ms']:.3f} ms wall**",
        f"- standard stage1 parser outside FileConfig.load + immutable copy: **{segments['standard_stage1_parser_residual_wall_ms']:.3f} ms wall**",
        '', '## Parser aggregates', ''
    ]
    for parser_type, data in parser_summary.items():
        lines.append(
            f"- `{parser_type}` — {data['classification']}; initial={data['initial']['calls']}, "
            f"stage1={data['stage1']['calls']}, shared parser identities={data['shared_parser_identity_count']}")
    Path(args.markdown_output).write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()

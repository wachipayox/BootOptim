#!/usr/bin/env python3
import argparse, json
from collections import Counter, defaultdict
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--trace', required=True)
p.add_argument('--json-output', required=True)
p.add_argument('--markdown-output', required=True)
a=p.parse_args()

events=[json.loads(line) for line in Path(a.trace).read_text().splitlines() if line.strip()]
header=next((e for e in events if e['kind']=='profile_header'), None)
scopes=defaultdict(list)
for e in events:
    if e['kind']!='profile_header': scopes[e['kind']].append(e)

def totals(kind):
    xs=scopes.get(kind, [])
    wall=sum(x['duration_ns'] for x in xs)/1e6
    cpus=[x['cpu_ns'] for x in xs if x['cpu_ns']>=0]
    cpu=sum(cpus)/1e6 if cpus else None
    throws=Counter((x.get('detail') or '') for x in xs if x.get('detail'))
    return {'count':len(xs),'wall_ms':wall,'thread_cpu_ms':cpu,'throws':dict(throws)}

order=['stage2_validation','validate_languages','find_language','add_access_transformers','add_mixin_configs','add_enum_extenders','add_for_scanning']
summary={'header':header,'scopes':{k:totals(k) for k in order}}
stage=summary['scopes']['stage2_validation']['wall_ms']
known=sum(summary['scopes'][k]['wall_ms'] for k in ['validate_languages','add_access_transformers','add_mixin_configs','add_enum_extenders','add_for_scanning'])
summary['stage2_unattributed_excluding_sorter_ms']=stage-known
Path(a.json_output).write_text(json.dumps(summary, indent=2, sort_keys=True))
lines=['# Agent 138 FML language/profile summary','',f"header: `{(header or {}).get('detail','missing')}`",'', '| scope | count | wall ms | thread CPU ms | throws |','|---|---:|---:|---:|---:|']
for k in order:
    s=summary['scopes'][k]
    cpu='n/a' if s['thread_cpu_ms'] is None else f"{s['thread_cpu_ms']:.3f}"
    lines.append(f"| `{k}` | {s['count']} | {s['wall_ms']:.3f} | {cpu} | {sum(s['throws'].values())} |")
lines += ['', '> `find_language` is nested inside `validate_languages`; do not add nested wall values.', '', f"Stage-2 wall outside validate/post-sort scopes (still includes ModSorter and framework bookkeeping): **{summary['stage2_unattributed_excluding_sorter_ms']:.3f} ms**."]
Path(a.markdown_output).write_text('\n'.join(lines)+'\n')

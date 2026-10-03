"""Validate bounded ancestry census; sampled inclusive CPU is not an exact savings budget."""
import json,re,sys
from pathlib import Path
KINDS={"PARENTS","DEPENDENCIES","MATERIAL","TEXTURE_ENTRY","ELEMENTS","ROOT","TRANSFORMS"}
def validate(log):
    lines=re.findall(r"BOOTOPTIM_MODEL_ANCESTRY (.*)",log)
    if len(lines)!=14:raise ValueError("exactly14 phase/kind rows required")
    rows={}
    numeric="calls nested samples cpu_valid alloc_valid cpu_ns wall_ns allocated_bytes failures map_probes map_hits alias_checks direct aliased already_linked cycle_sets chain_lists".split()
    for line in lines:
        fields=dict(re.findall(r"(\w+)=(\S+)",line));key=(fields['phase'],fields['kind'])
        if key in rows or key[0] not in {"outside_bake","bake"} or key[1] not in KINDS:raise ValueError("invalid/duplicate scope")
        r={k:int(fields[k]) for k in numeric}
        if any(v<0 for v in r.values()):raise ValueError("negative counter")
        if r['nested']>r['calls'] or r['samples']>r['calls']-r['nested']:raise ValueError("nested sampling invalid")
        if r['cpu_valid']!=r['samples'] or r['alloc_valid']!=r['samples']:raise ValueError("unsupported CPU/allocation")
        if r['map_hits']>r['map_probes'] or r['already_linked']>r['calls']:raise ValueError("invalid probe counts")
        if key[1]=='MATERIAL' and r['direct']+r['aliased']!=r['calls']:raise ValueError("material census incomplete")
        if key[1]=='PARENTS' and r['cycle_sets']!=r['calls']:raise ValueError('parent set factory hook inactive')
        if key[1]=='MATERIAL' and r['chain_lists']!=r['calls']:raise ValueError('material list factory hook inactive')
        if r['failures']:raise ValueError("original operation threw")
        rows[key]=r
    sums=re.findall(r"BOOTOPTIM_MODEL_ANCESTRY_SUMMARY (.*)",log)
    if len(sums)!=1:raise ValueError("one closure required")
    summary=dict(re.findall(r"(\w+)=(\S+)",sums[0]))
    if summary['inflight']!='0' or summary['overflow']!='false':raise ValueError("unfinished/overflowed census")
    for key in [('outside_bake','PARENTS'),('outside_bake','DEPENDENCIES'),('bake','MATERIAL'),('bake','TEXTURE_ENTRY')]:
        r=rows[key]
        if r['calls']==0 or r['samples']==0 or r['cpu_ns']==0:raise ValueError("required owner inactive/unmeasured: "+str(key))
    probes=sum(r['map_probes'] for (phase,kind),r in rows.items() if kind=='TEXTURE_ENTRY')
    entries=sum(r['calls'] for (phase,kind),r in rows.items() if kind=='TEXTURE_ENTRY')
    if probes<entries:raise ValueError("texture-map operation hook inactive")
    if sum(r['alias_checks'] for r in rows.values())==0:raise ValueError("alias operation hook inactive")
    return {'valid':True,'scope':'sampled original inclusive method scopes; NOT additive or exact savings',
            'sampling':'whitened expected1/256; suppressed same-kind recursion',
            'rows':[dict(phase=phase,kind=kind,**r) for (phase,kind),r in rows.items()], 'ttmm_claim':False}
if __name__=='__main__':print(json.dumps(validate(Path(sys.argv[1]).read_text(encoding='utf-8',errors='replace')),indent=2))

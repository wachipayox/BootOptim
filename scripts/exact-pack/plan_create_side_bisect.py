#!/usr/bin/env python3
"""Bisect one accepted schema-2 artifact scope into Create-connected vs residual physical units."""
from __future__ import annotations
import argparse, json, sys, zipfile
from functools import lru_cache
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import artifact_scaling as base
import artifact_scaling_physical as physical


def load_scope(path: Path, pack_fingerprint: str, inventory: dict[str, dict]) -> set[str]:
    data=json.loads(path.read_text(encoding='utf-8'))
    if data.get('source_pack_fingerprint') != pack_fingerprint:
        raise ValueError('parent scope source_pack_fingerprint does not match current exact pack')
    expected=data.get('selected_artifact_fingerprints') or {}
    if not expected:
        raise ValueError('parent scope has no selected_artifact_fingerprints')
    if set(expected)-set(inventory):
        raise ValueError(f'parent scope references missing artifacts: {sorted(set(expected)-set(inventory), key=str.lower)}')
    mismatched=[name for name,sha in expected.items() if inventory[name]['sha256'] != sha]
    if mismatched:
        raise ValueError(f'parent scope artifact hash mismatch: {sorted(mismatched, key=str.lower)}')
    return set(expected)


def required_depends_on_create(records, mod_id: str) -> bool:
    @lru_cache(None)
    def visit(mid: str, stack: tuple[str,...]=()) -> bool:
        if mid == 'create': return True
        if mid in base.PLATFORM_PROVIDED_MOD_IDS or mid in stack: return False
        deps=set()
        for rec in records.get(mid, []): deps.update(rec.dependencies.get(mid,set()))
        return any(visit(dep, stack+(mid,)) for dep in deps)
    return visit(mod_id)


def make_variant(name, keep_label, scope, assigned, records, artifact_to_ids, units, inherited):
    candidate=set(scope)-set(assigned)
    selected, reasons=physical.prune_unrunnable(records, artifact_to_ids, units, candidate)
    # Defensive: pruning must never re-introduce artifacts outside the accepted parent scope.
    selected &= set(scope)
    decisions=[]
    for artifact in sorted(artifact_to_ids,key=str.lower):
        if artifact in selected:
            status, reason, missing='included','retained_dependency_closed_subscope',[]
        elif artifact in inherited:
            status, reason, missing='excluded','inherited_parent_closure_exclusion',[]
        elif artifact in assigned:
            status, reason, missing='excluded','subpartition_unit_exclusion',[]
        else:
            detail=reasons.get(artifact,{})
            status='excluded'; reason=detail.get('reason','subscope_closure_not_runnable'); missing=detail.get('missing_dependencies',[])
            if reason=='closure_depends_on_excluded_or_missing': reason='subscope_closure_depends_on_excluded_or_missing'
            elif reason=='compatibility_unit_member_unrunnable': reason='subscope_compatibility_unit_member_unrunnable'
        decisions.append({'artifact':artifact,'status':status,'reason':reason,'missing_dependencies':missing,'mod_ids':sorted(artifact_to_ids[artifact])})
    effective=set(artifact_to_ids)-selected
    subclosure=effective-set(inherited)-set(assigned)
    return {
      'id':name,'kind':'scoped_physical_bisection','keep_label':keep_label,
      'roots':sorted({m for a in selected for m in artifact_to_ids[a]}),
      'runnable_roots':sorted({m for a in selected for m in artifact_to_ids[a]}),
      'mod_ids':sorted({m for a in selected for m in artifact_to_ids[a]}),
      'artifacts':sorted(selected,key=str.lower),'assigned_artifacts':sorted(assigned,key=str.lower),
      'direct_excluded_artifacts':sorted(assigned,key=str.lower),
      'effective_excluded_artifacts':sorted(effective,key=str.lower),
      'subscope_closure_removed_artifacts':sorted(subclosure,key=str.lower),
      'artifact_decisions':decisions,'missing_dependencies':[],
    }


def build(pack_dir: Path, scope_manifest: Path, compatibility_groups, runtime_symbol_providers):
    pack_dir=pack_dir.resolve()
    records, artifact_to_ids, fingerprint=base.scan_pack(pack_dir)
    runtime_edges=base.add_runtime_symbol_provider_edges(pack_dir,records,artifact_to_ids,runtime_symbol_providers)
    parsed=[base.parse_group(raw)[1] for raw in compatibility_groups]
    units=physical.artifact_units(records,artifact_to_ids,parsed)
    inventory_list=base._artifact_inventory(records,artifact_to_ids,units)
    inventory={x['name']:x for x in inventory_list}
    scope=load_scope(scope_manifest,fingerprint,inventory)
    crossing=[]
    for u in units:
        inside=set(u['artifacts']) & scope
        if inside and inside != set(u['artifacts']): crossing.append(u['id'])
    if crossing: raise ValueError(f'parent scope splits compatibility artifact units: {crossing}')
    scope_units=[u for u in units if set(u['artifacts']) <= scope]
    create_units=[]; residual_units=[]
    for u in scope_units:
        target=create_units if any(required_depends_on_create(records,m) for m in u['mod_ids']) else residual_units
        target.append(u)
    create_assigned={a for u in create_units for a in u['artifacts']}
    residual_assigned={a for u in residual_units for a in u['artifacts']}
    if create_assigned & residual_assigned: raise ValueError('subpartition assigns a physical artifact to both arms')
    if create_assigned | residual_assigned != scope: raise ValueError('subpartition does not cover accepted parent scope')
    if not any('create' in u['mod_ids'] for u in create_units): raise ValueError('Create physical unit is not in create-connected arm')
    inherited=set(artifact_to_ids)-scope
    full_decisions=[]
    for a in sorted(artifact_to_ids,key=str.lower):
        full_decisions.append({'artifact':a,'status':'included' if a in scope else 'excluded','reason':'parent_scope_member' if a in scope else 'inherited_parent_closure_exclusion','missing_dependencies':[],'mod_ids':sorted(artifact_to_ids[a])})
    full={
      'id':'scope-full','kind':'accepted_parent_scope','roots':sorted({m for a in scope for m in artifact_to_ids[a]}),
      'runnable_roots':sorted({m for a in scope for m in artifact_to_ids[a]}),'mod_ids':sorted({m for a in scope for m in artifact_to_ids[a]}),
      'artifacts':sorted(scope,key=str.lower),'assigned_artifacts':[], 'direct_excluded_artifacts':[],
      'effective_excluded_artifacts':sorted(inherited,key=str.lower),'artifact_decisions':full_decisions,'missing_dependencies':[]}
    retain_create=make_variant('subscope-retain-create','create-connected',scope,residual_assigned,records,artifact_to_ids,units,inherited)
    without_create=make_variant('subscope-without-create','non-create-residual',scope,create_assigned,records,artifact_to_ids,units,inherited)
    eff1=set(retain_create['effective_excluded_artifacts']); eff2=set(without_create['effective_excluded_artifacts'])
    overlap=eff1 & eff2
    shared_scope_closure=overlap-inherited
    validation={
      'valid': True,
      'parent_scope_artifact_count':len(scope),'parent_inherited_exclusion_count':len(inherited),
      'create_connected_artifact_count':len(create_assigned),'residual_artifact_count':len(residual_assigned),
      'assigned_artifact_intersection':[], 'assigned_artifact_union_count':len(create_assigned|residual_assigned),
      'effective_exclusion_overlap_count':len(overlap),
      'effective_overlap_inherited_parent_artifacts':sorted(overlap & inherited,key=str.lower),
      'effective_overlap_subscope_closure_artifacts':sorted(shared_scope_closure,key=str.lower),
    }
    return {
      'schema':2,'selection_unit':'top_level_physical_artifact','pack_directory':str(pack_dir),'pack_fingerprint':fingerprint,
      'artifact_unit_fingerprint':base._unit_fingerprint(units,inventory_list),'artifact_count':len(artifact_to_ids),'artifact_unit_count':len(units),
      'partition_validation':validation,
      'compatibility_groups':[{'name':raw.split('=',1)[0].strip(),'mod_ids':sorted(vals)} for raw,vals in zip(compatibility_groups,parsed)],
      'explicitly_excluded_roots':[],'runtime_symbol_provider_edges':runtime_edges,'artifact_units':units,'artifacts':inventory_list,
      'mods':[{'id':mid,'artifacts':sorted({r.artifact for r in records[mid]},key=str.lower),'versions':sorted({r.version for r in records[mid] if r.version}),
               'required_dependencies':sorted({d for r in records[mid] for d in r.dependencies.get(mid,set())}),
               'optional_dependencies':sorted({d for r in records[mid] for d in r.optional_dependencies.get(mid,set())})} for mid in sorted(records)],
      'scope_manifest':str(scope_manifest),'variants':[full,retain_create,without_create]}


def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--pack-dir',type=Path,required=True); ap.add_argument('--scope-manifest',type=Path,default=HERE/'fixtures'/'agent118-complement1-scope.json'); ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--compatibility-group',action='append',default=[]); ap.add_argument('--runtime-symbol-provider',action='append',default=[])
    ap.add_argument('--baseline',action='append',default=[]); ap.add_argument('--group',action='append',default=[]); ap.add_argument('--complement-group',action='append',default=[]); ap.add_argument('--exclude-root',action='append',default=[]); ap.add_argument('--balanced-partitions',type=int,default=0)
    args=ap.parse_args()
    if args.group != ['agent120-scope=create-side']:
        raise SystemExit('agent120 scoped planner requires exactly --group agent120-scope=create-side')
    if args.baseline or args.complement_group or args.balanced_partitions:
        raise SystemExit('agent120 scoped planner does not accept baseline/complement/balanced partition directives')
    try:
        plan=build(args.pack_dir,args.scope_manifest,args.compatibility_group,args.runtime_symbol_provider)
        records, _, _ = base.scan_pack(args.pack_dir.resolve())
        scope=set(json.loads(args.scope_manifest.read_text(encoding='utf-8'))['selected_artifact_fingerprints'])
        for root in args.exclude_root:
            providers={r.artifact for r in records.get(root,[])}
            if providers & scope:
                raise ValueError(f'operator exclusion {root} intersects accepted parent scope')
    except (OSError,ValueError,zipfile.BadZipFile,json.JSONDecodeError) as e: raise SystemExit(str(e)) from e
    args.output.write_text(json.dumps(plan,indent=2,sort_keys=True)+'\n',encoding='utf-8')
    v=plan['partition_validation']
    print(json.dumps(v,indent=2,sort_keys=True))

if __name__=='__main__': main()

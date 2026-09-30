"""Offline sweep results: one-run exploratory deltas, never an A/B significance claim."""
import json
import re
import sys
from pathlib import Path
from check_resource_selection import check

def summarize(root):
    root = Path(root)
    summary = json.loads((root / 'summary.json').read_text(encoding='utf-8-sig'))
    expected = ['control', 'decocraft-v2', 'ferrite-capacity', 'sodium-axis', 'layer-delta']
    feature_keys = dict(zip(expected[1:], ['experimentalDecocraftCornerRotationReuseV2', 'ferriteCoreQuadCapacity', 'sodiumAxisQuadFlags', 'generatedItemLayerDeltaHoist']))
    issues = []
    runs = summary['runs']
    if summary['status'] != 'finished' or [r['name'] for r in runs] != expected:
        issues.append('Incomplete or failed sweep')
    shas = {r['jarSha256'] for r in runs}
    if len(shas) != 1:
        issues.append('JAR differs between configurations')
    for run in runs:
        folder = root / run['name']
        if not run['valid'] or not run['restored']:
            issues.append(f"{run['name']}: invalid or not restored")
        try:
            selection = check(folder / 'options.before.txt', folder / 'options.after.txt', folder / 'latest.log')
            reload_count = 2 if run.get('menuReloadMs') is not None else 1
            if not selection['valid'] or selection['reload_count'] != reload_count:
                issues.append(f"{run['name']}: effective pack selection or generation count invalid")
            log = (folder / 'latest.log').read_text(encoding='utf-8-sig', errors='replace')
            if re.search(r'(?:Mixin apply|Mixin transformation|Critical injection).*boot_optim|boot_optim.*(?:MixinApplyError|InvalidMixinException|InjectionError)', log, re.I):
                issues.append(f"{run['name']}: BootOptim Mixin failure")
            transaction = json.loads((folder / 'transaction.finished.json').read_text(encoding='utf-8-sig'))
            if not transaction['valid'] or not transaction['effectiveCommandLineSha256'] or not transaction['javaCreationDate']:
                issues.append(f"{run['name']}: missing effective process identity")
            effective = transaction.get('validatedRequiredJvmArgs', [])
            for mode, key in feature_keys.items():
                flag = f"-Dboot_optim.{key}={'true' if run['name'] == mode else 'false'}"
                if effective.count(flag) != 1:
                    issues.append(f"{run['name']}: wrong effective flag {key}")
            marker = {'decocraft-v2': r'BOOTOPTIM_DECOCRAFT_CORNER_ROTATION mode=substitute_v2',
                      'ferrite-capacity': r'BOOTOPTIM_FERRITE_QUAD_CAPACITY .*success=true',
                      'sodium-axis': r'BOOTOPTIM_SODIUM_AXIS_QUAD_FLAGS status=active'}.get(run['name'])
            if marker and not re.search(marker, log):
                issues.append(f"{run['name']}: requested target did not report activation")
        except (OSError, ValueError, KeyError) as error:
            issues.append(f"{run['name']}: incomplete evidence: {error}")
    baseline = runs[0]['startupMs'] if runs and runs[0]['name'] == 'control' else None
    result = dict(valid=not issues, issues=issues, origin='physical_laptop',
                  clock='jvm_uptime', endpoint='main_menu_presented_after_initial_reload',
                  cache_state='session_uncontrolled', conclusion='exploratory_only_not_performance_proof', runs=runs)
    text = ['# Laptop exploratory sweep', '', 'One run per configuration; order and HDD page cache are uncontrolled. Repeat paired controls before a performance claim.', '', '| Configuration | JVM→menu ms | Initial reload complete ms | Menu reload ms | GC ms | Delta vs first control ms |', '| --- | ---: | ---: | ---: | ---: | ---: |']
    for r in runs:
        delta = r['startupMs'] - baseline if r['startupMs'] is not None and baseline is not None else None
        text.append(f"| {r['name']} | {r['startupMs']} | {r['initialReloadCompleteMs']} | {r.get('menuReloadMs')} | {r['gcMs']} | {delta} |")
    text += ['', 'Validity: ' + ('PASS' if not issues else 'FAIL: ' + '; '.join(issues))]
    (root / 'checked-summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    (root / 'summary.md').write_text('\n'.join(text) + '\n', encoding='utf-8')
    return result

if __name__ == '__main__':
    result = summarize(sys.argv[1])
    print(json.dumps(result, indent=2))
    sys.exit(0 if result['valid'] else 1)

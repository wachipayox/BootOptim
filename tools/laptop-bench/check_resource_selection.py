"""Check benchmark resource selection against a reference options.txt (read-only)."""
import argparse
import json
from pathlib import Path
import sys


def selection(path):
    lines = [line.split(':', 1)[1] for line in path.read_text(encoding='utf-8-sig').splitlines()
             if line.startswith('resourcePacks:')]
    if len(lines) != 1:
        raise ValueError(f'{path}: expected exactly one resourcePacks entry')
    packs = json.loads(lines[0])
    if not isinstance(packs, list) or any(not isinstance(p, str) for p in packs):
        raise ValueError(f'{path}: resourcePacks must be a list of strings')
    if len(packs) != len(set(packs)):
        raise ValueError(f'{path}: duplicate pack identifiers')
    return packs


def check(reference, actual, log=None):
    expected = selection(reference)
    observed = selection(actual)
    if not any(p.startswith('file/') for p in expected):
        raise ValueError('Reference has no external resource packs; cannot validate exact-pack')
    issues = []
    if observed != expected:
        issues.append('Selected packs or priority order differ from reference')
    reloads = []
    if log:
        text = log.read_text(encoding='utf-8-sig', errors='replace')
        reloads = [line.split('Reloading ResourceManager:', 1)[1].strip()
                   for line in text.splitlines() if 'Reloading ResourceManager:' in line]
        if not reloads:
            issues.append('No effective resource-reload list in log')
        external = [p for p in expected if p.startswith('file/')]
        for index, reload in enumerate(reloads):
            # IDs for combined mod packs may themselves contain commas. External
            # file IDs in this fixture do not; do not tokenize all mod IDs.
            if any(',' in p for p in external):
                raise ValueError('Comma in external pack ID is unsupported by log check')
            tokens = [p.strip() for p in reload.split(',')]
            effective = [p for p in tokens if p.startswith('file/')]
            if effective != external:
                issues.append(f'Reload {index + 1}: external packs/order differ from reference')
        if 'Caught error loading resourcepacks' in text:
            issues.append('Resource-pack fallback reported')
    return dict(valid=not issues, issues=issues, expected=expected, observed=observed,
                reload_count=len(reloads), scope='resource selection only; not complete benchmark validity')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', required=True, type=Path)
    parser.add_argument('--options', required=True, type=Path)
    parser.add_argument('--log', type=Path, help='Read only after the measured Java process exits')
    args = parser.parse_args()
    try:
        result = check(args.reference, args.options, args.log)
    except (OSError, ValueError) as error:
        result = dict(valid=False, issues=[str(error)])
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['valid'] else 1


if __name__ == '__main__':
    sys.exit(main())

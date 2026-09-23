#!/usr/bin/env python3
"""Offline contract for a changed-pack reload followed by restored selection."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from check_resource_selection import selection


def _option_list(path: Path, key: str):
    values = [line.split(":", 1)[1] for line in path.read_text(encoding="utf-8-sig").splitlines()
              if line.startswith(key + ":")]
    if len(values) != 1:
        raise ValueError(f"{path}: expected exactly one {key} entry")
    value = json.loads(values[0])
    if not isinstance(value, list) or any(not isinstance(p, str) for p in value):
        raise ValueError(f"{path}: {key} must be a list of strings")
    return value


def check(before: Path, after: Path, log: Path, variance: dict):
    expected = selection(before)
    final = selection(after)
    expected_incompatible = _option_list(before, "incompatibleResourcePacks")
    final_incompatible = _option_list(after, "incompatibleResourcePacks")
    external = [p for p in expected if p.startswith("file/")]
    issues = []
    if not external or any("," in p for p in external):
        issues.append("reference_external_pack_contract_invalid")
    if final != expected or final_incompatible != expected_incompatible:
        issues.append("final_pack_selection_or_compatibility_differs_from_before")
    if not variance.get("valid") or variance.get("profile") not in ("multi_reload", "multi_reload_deep"):
        issues.append("multi_reload_variance_invalid")

    text = log.read_text(encoding="utf-8-sig", errors="replace")
    effective = []
    for line in text.splitlines():
        if "Reloading ResourceManager:" in line:
            portion = line.split("Reloading ResourceManager:", 1)[1]
            effective.append([token.strip() for token in portion.split(",")
                              if token.strip().startswith("file/")])
    generations = [row["reload_id"] for row in variance.get("reload_summaries", [])]
    if len(effective) < 3 or len(effective) != len(generations):
        issues.append(f"effective_reload_count_mismatch:{len(effective)}:{len(generations)}")
    if effective and effective[0] != external:
        issues.append("initial_effective_packs_differ_from_before")
    if effective and effective[-1] != external:
        issues.append("last_effective_packs_not_restored")
    if len(effective) >= 3 and not any(packs != external for packs in effective[1:-1]):
        issues.append("no_changed_intermediate_effective_reload")
    if "Caught error loading resourcepacks" in text:
        issues.append("resource_pack_fallback_reported")
    return {"valid": not issues, "issues": issues, "before": expected, "after": final,
            "incompatible_before": expected_incompatible,
            "incompatible_after": final_incompatible,
            "effective_external_by_reload": effective,
            "scope": "pack selection and effective reload order, not visual or in-world equivalence"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, type=Path)
    parser.add_argument("--after", required=True, type=Path)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--variance-json", required=True, type=Path)
    args = parser.parse_args()
    try:
        doc = json.loads(args.variance_json.read_text(encoding="utf-8-sig"))
        if len(doc) == 1 and "profile" not in doc:
            doc = next(iter(doc.values()))
        result = check(args.before, args.after, args.log, doc)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"valid": False, "issues": [str(error)]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    sys.exit(main())

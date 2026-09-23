#!/usr/bin/env python3
"""Check that the exact-pack Decocraft batch operated in each reload generation."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


MARKER = "BOOTOPTIM_DECOCRAFT_MODEL_BATCH "
FIELDS = re.compile(r"([a-z_]+)=([^\s]+)")
EXPECTED_COUNT = 10_809
EXPECTED_BYTES = 3_129_313
EXPECTED_DIGEST = "7e1ceb06798205056f50669b4c5edde3c95b35eabd7ca5a78416b884754e68d7"


def check(log: Path) -> dict:
    rows = []
    for line in log.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        if MARKER in line:
            rows.append(dict(FIELDS.findall(line.split(MARKER, 1)[1])))

    issues = []
    ready = [row for row in rows if row.get("status") == "ready"]
    enabled = [row for row in rows if row.get("status") == "enabled"]
    complete = [row for row in rows if row.get("status") == "complete"]
    disabled = [row for row in rows if row.get("status") in ("disabled", "invalidated")]
    if len(ready) != 1 or ready[0].get("entries") != str(EXPECTED_COUNT) \
            or ready[0].get("bytes") != str(EXPECTED_BYTES) \
            or ready[0].get("digest") != EXPECTED_DIGEST:
        issues.append("snapshot_not_ready_once_with_exact_corpus")
    if len(enabled) != 3 or [row.get("generation") for row in enabled] != ["1", "2", "3"]:
        issues.append("expected_three_enabled_generations")
    if len(complete) != 3 or [row.get("generation") for row in complete] != ["1", "2", "3"]:
        issues.append("expected_three_complete_generations")
    if disabled:
        issues.append("batch_disabled_or_invalidated")
    for index, row in enumerate(enabled, 1):
        if row.get("archive_present") != "true" or row.get("verify") != "false":
            issues.append(f"generation_{index}_archive_or_verify_contract_invalid")
    for index, row in enumerate(complete, 1):
        if (row.get("success") != "true" or row.get("hits") != str(EXPECTED_COUNT)
                or row.get("fallbacks") != "0" or row.get("verified") != "0"
                or row.get("retained_bytes") != str(EXPECTED_BYTES)):
            issues.append(f"generation_{index}_batch_did_not_cover_exact_corpus")
    return {"valid": not issues, "issues": issues, "ready": ready,
            "enabled": enabled, "complete": complete, "disabled": disabled}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    try:
        result = check(args.log)
    except OSError as error:
        result = {"valid": False, "issues": [str(error)]}
    print(json.dumps(result, indent=2))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    sys.exit(main())

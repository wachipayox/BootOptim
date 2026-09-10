#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from parse_lifecycle import parse

MARKERS = (
    "BOOTOPTIM_ML_FORK_REQUEST_END ",
    "BOOTOPTIM_ML_FORK_REQUEST ",
    "BOOTOPTIM_ML_FORK ",
    "BOOTOPTIM_MIXIN_LIFECYCLE ",
    "BOOTOPTIM_MAIN_LIFECYCLE ",
)


def strip_logging_prefixes(text: str) -> str:
    cleaned: list[str] = []
    for line in text.splitlines():
        for marker in MARKERS:
            if marker in line:
                line = marker + line.split(marker, 1)[1]
                break
        cleaned.append(line)
    return "\n".join(cleaned) + ("\n" if text.endswith("\n") else "")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    text = args.console.read_text(encoding="utf-8", errors="replace")
    result = parse(strip_logging_prefixes(text))
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("BOOTOPTIM_MIXIN_PREPARECONFIGS_PROFILE " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Run one exact-pack startup with an external low-retention JFR recording.

This is an attribution diagnostic, not a runtime optimization. The recording
is started by the JVM itself, so BootOptim does not add counters, object maps,
or per-model logging to the ModelBakery path. The existing run_startup.py
performs endpoint/resource validation after the process exits.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path


def _safe_output(root: Path, requested: str) -> Path:
    output = (root / requested).resolve()
    try:
        output.relative_to(root.resolve())
    except ValueError as exc:
        raise SystemExit("--output must remain inside the repository") from exc
    if output.suffix.lower() != ".jfr":
        raise SystemExit("--output must end in .jfr")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", required=True)
    parser.add_argument("--iteration", required=True, type=int)
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument(
        "--output",
        default="diagnostics/modelmanager-attribution.jfr",
        help="repository-relative recording path",
    )
    parser.add_argument("--rerun-tasks", action="store_true")
    args = parser.parse_args()

    root = Path.cwd().resolve()
    output = _safe_output(root, args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    settings = (root / "scripts/exact-pack/modelmanager-attribution.jfc").resolve()
    if not settings.is_file():
        raise SystemExit(f"JFR settings file is missing: {settings}")

    existing = os.environ.get("BOOTOPTIM_PACK_EXTRA_JVM_ARGS", "")
    if "-XX:StartFlightRecording" in existing:
        raise SystemExit("BOOTOPTIM_PACK_EXTRA_JVM_ARGS already contains StartFlightRecording")
    recording_arg = (
        f"-XX:StartFlightRecording=filename={output},settings={settings},dumponexit=true"
    )
    env = os.environ.copy()
    env["BOOTOPTIM_PACK_EXTRA_JVM_ARGS"] = (
        f"{existing}\n{recording_arg}" if existing.strip() else recording_arg
    )

    command = [
        sys.executable,
        str(root / "scripts/exact-pack/run_startup.py"),
        "--variant",
        args.variant,
        "--iteration",
        str(args.iteration),
        "--timeout",
        str(args.timeout),
    ]
    if args.rerun_tasks:
        command.append("--rerun-tasks")

    completed = subprocess.run(command, cwd=root, env=env, check=False)
    if not output.is_file():
        print(f"JFR recording was not produced: {output}", file=sys.stderr)
        return completed.returncode or 2

    print(f"BOOTOPTIM_JFR recording={output} bytes={output.stat().st_size}")
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())

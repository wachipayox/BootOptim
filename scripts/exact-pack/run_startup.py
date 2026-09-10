#!/usr/bin/env python3
import runpy
import subprocess
import sys
from pathlib import Path


def option_value(name: str):
    try:
        index = sys.argv.index(name)
    except ValueError:
        return None
    if index + 1 >= len(sys.argv):
        return None
    return sys.argv[index + 1]


def main():
    script_dir = Path(__file__).resolve().parent
    base = script_dir / "_run_startup_scaling_base.py"
    runpy.run_path(str(base), run_name="__main__")

    variant = option_value("--variant") or ""
    trace = Path.cwd() / "run-pack-benchmark" / "logs" / "bootoptim-trace.jsonl"
    if variant.startswith("scaling-"):
        if not trace.is_file():
            raise SystemExit("Scaling early-modloading run requires bootoptim-trace.jsonl")
        command = [
            sys.executable,
            str(script_dir / "enrich_early_modloading.py"),
            "--result", str(Path.cwd() / "result.json"),
            "--trace", str(trace),
            "--require-resource-contract",
        ]
        subprocess.run(command, check=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Run an exact-pack control/candidate pair inside one hosted VM.

The normal exact-pack A/B matrix deliberately gives every run a fresh VM.  That
is useful for cold-start medians, but it also makes a single runner allocation
or storage disturbance look like an optimization.  This diagnostic keeps the
two processes in the same VM, alternates their order between pairs, and stores
each result before launching the next process.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


def copy_if_exists(source: Path, destination: Path) -> None:
    if source.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)


def clear_runtime_logs(root: Path) -> None:
    # The fixture has no mutable MCEF cache.  Removing these files before each
    # run prevents the second process from consuming stale reports or a cache
    # accidentally left by the first process.  The OS/Gradle caches remain
    # shared intentionally: this is a same-VM noise/paired-effect diagnostic,
    # not a replacement for the cold-start benchmark.
    for relative in (
        Path("result.json"),
        Path("exact-pack-console.log"),
        Path("exact-pack-thread-dump.log"),
        Path("run-pack-benchmark/logs"),
        Path("run-pack-benchmark/crash-reports"),
        Path("run-pack-benchmark/mods/mcef-cache"),
    ):
        target = root / relative
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
        else:
            target.unlink(missing_ok=True)


def start_host_trace(root: Path):
    """Capture low-overhead runner pressure while one pair is executing."""
    trace_path = root / "paired-results" / "host-vmstat.log"
    trace_path.parent.mkdir(parents=True, exist_ok=True)
    trace_handle = trace_path.open("w", encoding="utf-8", errors="replace")
    try:
        process = subprocess.Popen(
            # -y omits vmstat's first since-boot aggregate. Without it, the
            # first sample can describe the entire hosted VM lifetime rather
            # than the paired benchmark interval.
            ["vmstat", "-y", "-w", "1"],
            stdout=trace_handle,
            stderr=subprocess.STDOUT,
            text=True,
        )
    except (FileNotFoundError, OSError) as exc:
        trace_handle.write(f"vmstat unavailable: {exc}\n")
        trace_handle.flush()
        trace_handle.close()
        return None, None
    return process, trace_handle


def stop_host_trace(process, trace_handle) -> None:
    if process is None:
        return
    try:
        process.terminate()
        process.wait(timeout=5)
    except (subprocess.TimeoutExpired, OSError):
        process.kill()
        process.wait(timeout=5)
    finally:
        trace_handle.close()


def run_variant(
    root: Path,
    paired_root: Path,
    variant: str,
    pair: int,
    jvm_args: str,
    timeout: int,
    rerun_tasks: bool,
    order_label: str,
) -> None:
    clear_runtime_logs(root)
    env = os.environ.copy()
    env["BOOTOPTIM_PACK_EXTRA_JVM_ARGS"] = jvm_args
    command = [
        sys.executable,
        str(root / "scripts/exact-pack/run_startup.py"),
        "--variant",
        variant,
        "--iteration",
        str(pair),
        "--timeout",
        str(timeout),
    ]
    if rerun_tasks:
        command.append("--rerun-tasks")

    started = time.monotonic()
    completed = subprocess.run(command, cwd=root, env=env, check=False)
    elapsed_ms = round((time.monotonic() - started) * 1000.0, 3)

    destination = paired_root / f"{variant}-{pair}"
    destination.mkdir(parents=True, exist_ok=True)
    copy_if_exists(root / "result.json", destination / "result.json")
    result_path = destination / "result.json"
    if result_path.is_file():
        result = json.loads(result_path.read_text(encoding="utf-8"))
        result.update(
            {
                "paired_same_vm": True,
                "paired_pair": pair,
                "paired_order": order_label,
            }
        )
        result_path.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    copy_if_exists(root / "exact-pack-console.log", destination / "exact-pack-console.log")
    copy_if_exists(root / "exact-pack-thread-dump.log", destination / "exact-pack-thread-dump.log")
    copy_if_exists(
        root / "run-pack-benchmark/logs/latest.log",
        destination / "latest.log",
    )
    copy_if_exists(
        root / "run-pack-benchmark/logs/bootoptim-startup.log",
        destination / "bootoptim-startup.log",
    )
    for relative in (
        "config/boot_optim.properties",
        "config/fml.toml",
        "config/mcef/mcef.properties",
        "options.txt",
    ):
        copy_if_exists(root / "run-pack-benchmark" / relative, destination / relative)

    metadata = {
        "variant": variant,
        "pair": pair,
        "jvm_args": jvm_args,
        "returncode": completed.returncode,
        "elapsed_ms": elapsed_ms,
        "same_vm_pair": True,
    }
    (destination / "run-metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    if completed.returncode != 0:
        raise SystemExit(
            f"Paired exact-pack {variant} run failed with exit {completed.returncode}; "
            f"see {destination}"
        )
    if not (destination / "result.json").is_file():
        raise SystemExit(f"Paired exact-pack {variant} run produced no result.json: {destination}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pair", required=True, type=int)
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--rerun-tasks", action="store_true")
    args = parser.parse_args()

    control_args = os.environ.get("BOOTOPTIM_PAIRED_CONTROL_JVM_ARGS", "")
    candidate_args = os.environ.get("BOOTOPTIM_PAIRED_CANDIDATE_JVM_ARGS", "")
    if control_args == candidate_args:
        raise SystemExit("Paired mode requires different control and candidate JVM arguments.")

    root = Path.cwd()
    paired_root = root / "paired-results"
    paired_root.mkdir(parents=True, exist_ok=True)

    # Alternate order to expose a warm-second-run bias instead of attributing
    # it to one variant.  Pair deltas are later interpreted together with this
    # order metadata, never as a cold-start absolute benchmark.
    if args.pair % 2:
        sequence = (
            ("control", control_args, "control->candidate"),
            ("candidate", candidate_args, "control->candidate"),
        )
    else:
        sequence = (
            ("candidate", candidate_args, "candidate->control"),
            ("control", control_args, "candidate->control"),
        )

    host_trace_process, host_trace_handle = start_host_trace(root)
    try:
        for variant, jvm_args, order_label in sequence:
            run_variant(
                root,
                paired_root,
                variant,
                args.pair,
                jvm_args,
                args.timeout,
                args.rerun_tasks,
                order_label,
            )
    finally:
        stop_host_trace(host_trace_process, host_trace_handle)
        # Do not upload the last run a second time as root-level result.json.
        # The per-run copies above are the authoritative paired artifacts.
        clear_runtime_logs(root)


if __name__ == "__main__":
    main()

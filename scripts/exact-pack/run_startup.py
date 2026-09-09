from __future__ import annotations

import argparse
import hashlib
import http.server
import json
import os
import platform
import re
import shutil
import signal
import socketserver
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

DEFAULT_TIMEOUT_SECONDS = 1200
READY_PATTERNS = (
    "BOOTOPTIM_EXACT_PACK_READY",
    "Setting user:",
)
FAILURE_PATTERNS = (
    "Exception in thread \"main\"",
    "A fatal error has been detected by the Java Runtime Environment",
)
MODLAUNCHER_FORK_TRACE_FLAG = "-Dboot_optim.modlauncherForkTrace=true"
MODLAUNCHER_GAV = "cpw.mods:modlauncher:11.0.5"
MODLAUNCHER_CLASS = "cpw/mods/modlauncher/Launcher.class"
SECUREJARHANDLER_CLASS = "cpw/mods/jarhandling/SecureJar.class"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", required=True)
    parser.add_argument("--iteration", type=int, required=True)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--rerun-tasks", action="store_true")
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fork_requested() -> bool:
    extra = os.environ.get("BOOTOPTIM_PACK_EXTRA_JVM_ARGS", "")
    return MODLAUNCHER_FORK_TRACE_FLAG in extra.splitlines() or MODLAUNCHER_FORK_TRACE_FLAG in extra.split()


def _unique_artifact_files(resolution: dict) -> list[Path]:
    files: dict[str, Path] = {}
    for configuration in resolution.get("configurations", []):
        for artifact in configuration.get("artifacts", []):
            raw = artifact.get("file")
            if not raw:
                continue
            path = Path(raw).resolve()
            files[str(path)] = path
    return sorted(files.values(), key=lambda p: str(p))


def _jar_contains(path: Path, member: str) -> bool:
    try:
        with zipfile.ZipFile(path) as archive:
            return member in archive.namelist()
    except (OSError, zipfile.BadZipFile):
        return False


def _manifest_text(path: Path) -> str:
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
    except (OSError, KeyError, zipfile.BadZipFile):
        return ""


def verify_fork_resolution(root: Path) -> dict:
    build_report = json.loads((root / "modlauncher-fork-build.json").read_text(encoding="utf-8"))
    resolution = json.loads((root / "modlauncher-fork-resolution.json").read_text(encoding="utf-8"))
    artifacts = _unique_artifact_files(resolution)
    modlaunchers = [p for p in artifacts if _jar_contains(p, MODLAUNCHER_CLASS)]
    securejars = [p for p in artifacts if _jar_contains(p, SECUREJARHANDLER_CLASS)]
    if len(modlaunchers) != 1:
        raise SystemExit(f"Expected exactly one resolved ModLauncher core JAR, found {len(modlaunchers)}: {modlaunchers}")
    if len(securejars) != 1:
        raise SystemExit(f"Expected exactly one resolved SecureJarHandler core JAR, found {len(securejars)}: {securejars}")

    jar = modlaunchers[0]
    resolved_sha = sha256_file(jar)
    expected_sha = build_report["jar_sha256"]
    if resolved_sha != expected_sha:
        raise SystemExit(
            f"Resolved ModLauncher is not the fork artifact: resolved={jar} sha256={resolved_sha} expected={expected_sha}"
        )
    manifest = _manifest_text(jar)
    required_manifest_lines = (
        "BootOptim-Fork-Probe: agent94-post-accept-v2",
        "BootOptim-Upstream-Commit: 901c6ea849ae21ee7d464cd97113e77a6101a734",
    )
    missing = [line for line in required_manifest_lines if line not in manifest]
    if missing:
        raise SystemExit(f"Resolved fork manifest is missing {missing}: {jar}")

    identity = {
        "gav": MODLAUNCHER_GAV,
        "modlauncher": str(jar),
        "modlauncher_sha256": resolved_sha,
        "securejarhandler": str(securejars[0]),
        "resolution_file": str(root / "modlauncher-fork-resolution.json"),
    }
    print("BOOTOPTIM_ML_FORK_PREFLIGHT " + json.dumps(identity, sort_keys=True), flush=True)
    return identity


def start_mcef_mirror() -> tuple[socketserver.TCPServer, threading.Thread]:
    root = os.environ.get("BOOTOPTIM_MCEF_MIRROR_ROOT", "").strip()
    port_text = os.environ.get("BOOTOPTIM_MCEF_MIRROR_PORT", "").strip()
    if not root or not port_text:
        raise SystemExit("BOOTOPTIM_MCEF_MIRROR_ROOT and BOOTOPTIM_MCEF_MIRROR_PORT are required.")
    directory = Path(root).resolve()
    port = int(port_text)

    handler = lambda *args, **kwargs: http.server.SimpleHTTPRequestHandler(*args, directory=str(directory), **kwargs)
    server = socketserver.TCPServer(("127.0.0.1", port), handler)
    thread = threading.Thread(target=server.serve_forever, name="bootoptim-mcef-mirror", daemon=True)
    thread.start()
    return server, thread


def stop_process_tree(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        process.send_signal(signal.CTRL_BREAK_EVENT)
        try:
            process.wait(timeout=10)
            return
        except subprocess.TimeoutExpired:
            process.kill()
            return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=10)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def capture_thread_dump(output: Path) -> None:
    jcmd = shutil.which("jcmd")
    if not jcmd:
        return
    proc = subprocess.run([jcmd, "-l"], capture_output=True, text=True, check=False)
    pids = []
    for line in proc.stdout.splitlines():
        if "net.neoforged.devlaunch.Main" in line or "cpw.mods.bootstraplauncher.BootstrapLauncher" in line:
            pids.append(line.split(maxsplit=1)[0])
    chunks = []
    for pid in pids:
        dump = subprocess.run([jcmd, pid, "Thread.print"], capture_output=True, text=True, check=False)
        chunks.append(f"=== PID {pid} ===\n{dump.stdout}\n{dump.stderr}")
    if chunks:
        output.write_text("\n".join(chunks), encoding="utf-8")


def wait_for_process(process: subprocess.Popen, timeout_seconds: int) -> tuple[bool, str]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        code = process.poll()
        if code is not None:
            return True, f"process_exit_{code}"
        time.sleep(1)
    return False, "timeout"


def parse_console(console_log: Path) -> dict:
    text = console_log.read_text(encoding="utf-8", errors="replace") if console_log.exists() else ""
    return {
        "ready_marker": any(pattern in text for pattern in READY_PATTERNS),
        "failure_marker": next((pattern for pattern in FAILURE_PATTERNS if pattern in text), None),
        "fork_runtime_identity": "BOOTOPTIM_ML_FORK_IDENTITY probe=agent94-post-accept-v2" in text,
        "fork_target_begin": "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 class=net.minecraft.server.Bootstrap stage=class_transform_begin" in text,
        "fork_target_end": "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 class=net.minecraft.server.Bootstrap stage=class_transform_return" in text,
        "strict_transform_accept": "BOOTOPTIM_BOOTSTRAP_FORK_BOUNDARY event=transform_accept" in text,
        "bootstrap_entry": "BOOTOPTIM_BOOTSTRAP_FORK_BOUNDARY event=bootstrap_entry" in text,
        "console_size": len(text),
    }


def write_result(
    path: Path,
    *,
    args: argparse.Namespace,
    started_ns: int,
    finished_ns: int,
    process_return_code: int | None,
    reason: str,
    console_state: dict,
    fork_identity: dict | None,
) -> dict:
    result = {
        "schema": 2,
        "variant": args.variant,
        "iteration": args.iteration,
        "host": platform.platform(),
        "python": sys.version,
        "started_ns": started_ns,
        "finished_ns": finished_ns,
        "elapsed_seconds": (finished_ns - started_ns) / 1_000_000_000,
        "process_return_code": process_return_code,
        "reason": reason,
        "console": console_state,
        "modlauncher_fork_preflight": fork_identity,
    }
    path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def verify_resource_selection(root: Path, reference: Path, report: Path) -> None:
    actual = root / "run-pack-benchmark" / "options.txt"
    if not actual.exists():
        report.write_text(json.dumps({"ok": False, "reason": "run options missing"}, indent=2) + "\n", encoding="utf-8")
        return
    expected_bytes = reference.read_bytes()
    actual_bytes = actual.read_bytes()
    payload = {
        "ok": actual_bytes == expected_bytes,
        "reference_sha256": hashlib.sha256(expected_bytes).hexdigest(),
        "actual_sha256": hashlib.sha256(actual_bytes).hexdigest(),
    }
    report.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    args = parse_args()
    root = Path.cwd()
    console_log = root / "exact-pack-console.log"
    thread_dump = root / "exact-pack-thread-dump.log"
    result_json = root / "result.json"
    latest_log = root / "run-pack-benchmark" / "logs" / "latest.log"
    startup_log = root / "run-pack-benchmark" / "logs" / "bootoptim-startup.log"
    selection_report = root / "resource-selection-check.json"
    selection_reference = root / "resource-selection-reference.txt"
    for path in (console_log, thread_dump, result_json, selection_report, selection_reference):
        path.unlink(missing_ok=True)

    fixture_root = os.environ.get("BOOTOPTIM_PACK_DIR", "").strip()
    if not fixture_root:
        raise SystemExit("BOOTOPTIM_PACK_DIR is required for resource contract validation.")
    selection_reference.write_bytes((Path(fixture_root) / "options.txt").read_bytes())

    gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
    fork_identity = None
    if fork_requested():
        repo = root / ".agent94-ml-fork-repo"
        subprocess.run([
            sys.executable, "tools/modlauncher-fork-probe/prepare_runtime.py",
            "--root", str(root), "--output", str(repo), "--report", str(root / "modlauncher-fork-build.json")
        ], cwd=root, check=True)
        os.environ["BOOTOPTIM_ML_FORK_REPO"] = str(repo)
        # Resolve the exact ModDev launch inputs before the timed Minecraft JVM exists.
        subprocess.run([gradle, "preparePackBenchmarkClientRun", "--no-daemon", "--console=plain"], cwd=root, check=True)
        resolution = root / "modlauncher-fork-resolution.json"
        subprocess.run([
            gradle,
            "-I", "tools/modlauncher-fork-probe/resolve_launch_files.gradle",
            "agent94WriteLaunchResolution",
            f"-Pagent94LaunchResolutionOutput={resolution}",
            "--no-configuration-cache",
            "--no-daemon", "--console=plain"
        ], cwd=root, check=True)
        fork_identity = verify_fork_resolution(root)

    mirror_server = None
    process = None
    try:
        mirror_server, _ = start_mcef_mirror()
        command = [gradle, "runPackBenchmarkClient", "--no-daemon", "--console=plain"]
        if args.rerun_tasks:
            command.append("--rerun-tasks")
        print(f"Launching exact-pack benchmark variant={args.variant} iteration={args.iteration} timeout={args.timeout}s", flush=True)
        with console_log.open("w", encoding="utf-8", errors="replace") as output:
            kwargs = {"stdout": output, "stderr": subprocess.STDOUT, "cwd": root}
            if os.name == "nt":
                kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
            else:
                kwargs["start_new_session"] = True
            process = subprocess.Popen(command, **kwargs)
            process_finished, reason = wait_for_process(process, args.timeout)

        if not process_finished:
            capture_thread_dump(thread_dump)
            stop_process_tree(process)
        finished_ns = time.time_ns()
        return_code = process.poll() if process is not None else None
        console_state = parse_console(console_log)
        verify_resource_selection(root, selection_reference, selection_report)
        result = write_result(
            result_json,
            args=args,
            started_ns=started_ns,
            finished_ns=finished_ns,
            process_return_code=return_code,
            reason=reason,
            console_state=console_state,
            fork_identity=fork_identity,
        )
        print(json.dumps(result, sort_keys=True), flush=True)

        if not console_state["ready_marker"]:
            tail = ""
            if console_log.exists():
                lines = console_log.read_text(encoding="utf-8", errors="replace").splitlines()
                tail = "\n".join(lines[-120:])
            raise SystemExit(f"Exact-pack did not reach a ready marker ({reason}).\n{tail}")
        if console_state["failure_marker"]:
            raise SystemExit(f"Exact-pack emitted failure marker: {console_state['failure_marker']}")
        if fork_requested():
            missing = [key for key in ("fork_runtime_identity", "fork_target_begin", "fork_target_end", "strict_transform_accept", "bootstrap_entry") if not console_state[key]]
            if missing:
                raise SystemExit(f"Fork smoke reached ready state but missed required runtime markers: {missing}")
    finally:
        if mirror_server is not None:
            mirror_server.shutdown()
            mirror_server.server_close()
        if process is not None and process.poll() is None:
            stop_process_tree(process)


if __name__ == "__main__":
    started_ns = time.time_ns()
    main()

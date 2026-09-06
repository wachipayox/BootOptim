#!/usr/bin/env python3
import argparse
import http.server
import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

MARKER = "BOOTOPTIM_STARTUP phase=main_menu"


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("MCEF_LOCAL_MIRROR " + (fmt % args), flush=True)


def start_mcef_mirror() -> tuple[http.server.ThreadingHTTPServer | None, threading.Thread | None]:
    root_text = os.environ.get("BOOTOPTIM_MCEF_MIRROR_ROOT", "").strip()
    if not root_text:
        return None, None
    root = Path(root_text).resolve()
    if not root.is_dir():
        raise RuntimeError(f"BOOTOPTIM_MCEF_MIRROR_ROOT does not exist: {root}")
    port = int(os.environ.get("BOOTOPTIM_MCEF_MIRROR_PORT", "18765"))

    def handler(*args, **kwargs):
        return QuietHandler(*args, directory=str(root), **kwargs)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
    thread = threading.Thread(target=server.serve_forever, name="mcef-local-mirror", daemon=True)
    thread.start()
    print(f"MCEF local mirror listening on http://127.0.0.1:{port} root={root}", flush=True)
    return server, thread


def terminate_tree(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False, timeout=15)
        else:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
                return
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def capture_thread_dump(path: Path) -> None:
    lines: list[str] = []
    try:
        jps = subprocess.run(["jps", "-q"], capture_output=True, text=True, timeout=10, check=False)
        pids = [line.strip() for line in jps.stdout.splitlines() if line.strip().isdigit()]
    except Exception as exc:
        path.write_text(f"jps failed: {exc}\n", encoding="utf-8")
        return

    for pid in pids:
        lines.append(f"===== JVM {pid} =====\n")
        try:
            dump = subprocess.run(
                ["jcmd", pid, "Thread.print"],
                capture_output=True,
                text=True,
                timeout=15,
                check=False,
            )
            lines.append(dump.stdout)
            if dump.stderr:
                lines.append(dump.stderr)
        except Exception as exc:
            lines.append(f"jcmd failed: {exc}\n")
    path.write_text("".join(lines), encoding="utf-8", errors="replace")


def tail(path: Path, count: int = 250) -> str:
    if not path.exists():
        return ""
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-count:])


def wait_for_marker(process: subprocess.Popen, console_path: Path, timeout_seconds: int) -> tuple[bool, str]:
    deadline = time.monotonic() + timeout_seconds
    position = 0
    carry = ""
    while time.monotonic() < deadline:
        time.sleep(1.0)
        if console_path.exists():
            with console_path.open("r", encoding="utf-8", errors="replace") as handle:
                handle.seek(position)
                chunk = handle.read()
                position = handle.tell()
            if chunk:
                combined = carry + chunk
                if MARKER in combined:
                    return True, "marker"
                carry = combined[-len(MARKER):]
        code = process.poll()
        if code is not None:
            return False, f"process_exit_{code}"
    return False, "timeout"


def validate_variance_probe(console_log: Path, output_path: Path) -> None:
    console_text = console_log.read_text(encoding="utf-8", errors="replace")
    if "BOOTOPTIM_VARIANCE " not in console_text:
        return
    result = subprocess.run(
        [sys.executable, "tools/laptop-bench/variance_probe.py", str(console_log)],
        capture_output=True,
        text=True,
        check=False,
    )
    output_path.write_text(result.stdout or result.stderr, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise SystemExit(f"Variance-probe parser failed with exit {result.returncode}")
    try:
        payload = json.loads(result.stdout)
        summary = next(iter(payload.values()))
    except (json.JSONDecodeError, StopIteration, AttributeError) as exc:
        raise SystemExit(f"Variance-probe summary was not valid JSON: {exc}") from exc
    if not summary.get("valid"):
        raise SystemExit(
            "Variance-probe semantic gate failed: " + ", ".join(summary.get("invalid_reasons", []))
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", required=True)
    parser.add_argument("--iteration", required=True, type=int)
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()

    root = Path.cwd()
    console_log = root / "exact-pack-console.log"
    thread_dump = root / "exact-pack-thread-dump.log"
    result_json = root / "result.json"
    variance_summary = root / "variance-probe-summary.json"
    latest_log = root / "run-pack-benchmark" / "logs" / "latest.log"
    startup_log = root / "run-pack-benchmark" / "logs" / "bootoptim-startup.log"
    selection_report = root / "resource-selection-check.json"
    selection_reference = root / "resource-selection-reference.txt"
    for path in (console_log, thread_dump, result_json, variance_summary, selection_report, selection_reference):
        path.unlink(missing_ok=True)

    # Snapshot the fixture contract before launch; never derive expectations from
    # options that Minecraft may have rewritten after a failed resource reload.
    fixture_root = os.environ.get("BOOTOPTIM_PACK_DIR", "").strip()
    if not fixture_root:
        raise SystemExit("BOOTOPTIM_PACK_DIR is required for resource contract validation.")
    selection_reference.write_bytes((Path(fixture_root) / "options.txt").read_bytes())

    mirror_server = None
    process = None
    try:
        mirror_server, _ = start_mcef_mirror()
        gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
        command = [gradle, "runPackBenchmarkClient", "--no-daemon", "--console=plain"]
        print(
            f"Launching exact-pack benchmark variant={args.variant} iteration={args.iteration} "
            f"timeout={args.timeout}s",
            flush=True,
        )
        with console_log.open("w", encoding="utf-8", errors="replace") as output:
            kwargs = {
                "stdout": output,
                "stderr": subprocess.STDOUT,
                "cwd": root,
            }
            if os.name == "nt":
                kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
            else:
                kwargs["start_new_session"] = True
            process = subprocess.Popen(command, **kwargs)
            marker_found, reason = wait_for_marker(process, console_log, args.timeout)

        if not marker_found:
            capture_thread_dump(thread_dump)
            if process is not None:
                terminate_tree(process)
            print(tail(console_log), file=sys.stderr)
            if reason.startswith("process_exit_"):
                raise SystemExit(
                    f"Exact-pack benchmark exited before the main-menu marker ({reason})."
                )
            raise SystemExit(
                f"Exact-pack benchmark did not reach the main-menu marker within {args.timeout} seconds."
            )

        # Give BootOptim's exit-on-title path time to flush logs and stop child JVMs.
        try:
            process.wait(timeout=45)
        except subprocess.TimeoutExpired:
            terminate_tree(process)

        if not latest_log.is_file():
            raise SystemExit(f"Exact-pack run reached marker but latest.log is missing: {latest_log}")
        if not startup_log.is_file():
            raise SystemExit(f"Exact-pack run reached marker but startup report is missing: {startup_log}")

        latest_text = latest_log.read_text(encoding="utf-8", errors="replace")
        mixin_failures = (
            "InvalidInjectionException",
            "Mixin apply for mod boot_optim failed",
            "Mixin prepare for mod boot_optim failed",
        )
        if any(pattern in latest_text for pattern in mixin_failures):
            raise SystemExit("BootOptim Mixin failure detected in exact-pack latest.log.")

        # Diagnostic branches may emit early SERVICE-layer rows only to the captured console.
        # Validate them strictly after Java has exited; never poll latest.log while Java runs.
        validate_variance_probe(console_log, variance_summary)

        with selection_report.open("w", encoding="utf-8") as report:
            resource_check = subprocess.run(
                [sys.executable, "tools/laptop-bench/check_resource_selection.py",
                 "--reference", str(selection_reference),
                 "--options", str(root / "run-pack-benchmark" / "options.txt"),
                 "--log", str(latest_log)],
                cwd=root, stdout=report, check=False,
            )
        if resource_check.returncode != 0:
            raise SystemExit("Exact-pack resource contract failed; see resource-selection-check.json.")

        summary = subprocess.run(
            [
                sys.executable,
                "scripts/exact-pack/summarize_startup.py",
                "single",
                "--latest", str(latest_log),
                "--startup", str(startup_log),
                "--variant", args.variant,
                "--iteration", str(args.iteration),
                "--output", str(result_json),
            ],
            cwd=root,
            check=False,
        )
        if summary.returncode != 0:
            raise SystemExit(f"Exact-pack summarizer failed with exit {summary.returncode}")
    finally:
        if process is not None:
            terminate_tree(process)
        if mirror_server is not None:
            mirror_server.shutdown()
            mirror_server.server_close()


if __name__ == "__main__":
    main()

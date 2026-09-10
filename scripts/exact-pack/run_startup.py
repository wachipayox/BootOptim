#!/usr/bin/env python3
import argparse
import http.server
import os
import signal
import subprocess
import sys
import threading
from pathlib import Path

MARKER = "BOOTOPTIM_STARTUP phase=main_menu"
CONNECTOR_WARM_FLAG = "-Dboot_optim.profileConnectorWarm=true"


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
            dump = subprocess.run(["jcmd", pid, "Thread.print"], capture_output=True, text=True, timeout=15, check=False)
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


def wait_for_process(process: subprocess.Popen, timeout_seconds: int) -> tuple[bool, str]:
    try:
        process.wait(timeout=timeout_seconds)
        return True, f"process_exit_{process.returncode}"
    except subprocess.TimeoutExpired:
        return False, "timeout"


def maybe_run_connector_warm_profile() -> bool:
    extra = os.environ.get("BOOTOPTIM_PACK_EXTRA_JVM_ARGS", "")
    if CONNECTOR_WARM_FLAG not in extra:
        return False
    script = Path(__file__).with_name("run_connector_warm_profile.py")
    completed = subprocess.run([sys.executable, str(script), *sys.argv[1:]], cwd=Path.cwd(), check=False)
    if completed.returncode != 0:
        raise SystemExit(completed.returncode)
    return True


def main() -> None:
    if maybe_run_connector_warm_profile():
        return

    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", required=True)
    parser.add_argument("--iteration", required=True, type=int)
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--rerun-tasks", action="store_true")
    args = parser.parse_args()

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

    mirror_server = None
    process = None
    try:
        mirror_server, _ = start_mcef_mirror()
        gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
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
            if process is not None:
                terminate_tree(process)
            print(tail(console_log), file=sys.stderr)
            raise SystemExit(f"Exact-pack benchmark did not reach the main-menu marker within {args.timeout} seconds.")

        console_text = console_log.read_text(encoding="utf-8", errors="replace")
        if MARKER not in console_text:
            print(tail(console_log), file=sys.stderr)
            raise SystemExit(f"Exact-pack benchmark exited without the main-menu marker ({reason}).")
        if not latest_log.is_file():
            raise SystemExit(f"Exact-pack run reached marker but latest.log is missing: {latest_log}")
        if not startup_log.is_file():
            raise SystemExit(f"Exact-pack run reached marker but startup report is missing: {startup_log}")

        latest_text = latest_log.read_text(encoding="utf-8", errors="replace")
        mixin_failures = ("InvalidInjectionException", "Mixin apply for mod boot_optim failed", "Mixin prepare for mod boot_optim failed")
        if any(pattern in latest_text for pattern in mixin_failures):
            raise SystemExit("BootOptim Mixin failure detected in exact-pack latest.log.")

        with selection_report.open("w", encoding="utf-8") as report:
            resource_check = subprocess.run([
                sys.executable, "tools/laptop-bench/check_resource_selection.py",
                "--reference", str(selection_reference), "--options", str(root / "run-pack-benchmark" / "options.txt"),
                "--log", str(latest_log)], cwd=root, stdout=report, check=False)
        if resource_check.returncode != 0:
            raise SystemExit("Exact-pack resource contract failed; see resource-selection-check.json.")

        summary = subprocess.run([
            sys.executable, "scripts/exact-pack/summarize_startup.py", "single",
            "--latest", str(latest_log), "--startup", str(startup_log), "--variant", args.variant,
            "--iteration", str(args.iteration), "--output", str(result_json)], cwd=root, check=False)
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

# Prism / Windows physical benchmark harness state machine — 2026-09-07

Status: **METHODOLOGY AUDIT / TOOLING CONTRACT**. No runtime, operating-system, Java, driver, gameplay, or optimization change.

Authority audited: `agent/integration-current` @ `2bccf5f4fa221c78e286d052beb78636fa4c317b`.

This audit closes a recurring methodology failure mode in physical-laptop work: the benchmark must treat Prism, `instance.cfg`, Task Scheduler, and the spawned Java process as separate state authorities. A file edit is not proof that the running JVM received it, a scheduled-task state is not the Java-process state, and a menu marker is not proof that `Minecraft.stop()` actually terminated on Windows.

## Evidence that motivates the protocol

- The corrected P0.2 physical run reached `main_menu` at `350,330 ms`; its first attempt was discarded because Prism rewrote the JVM options and the variance property was absent.
- PR #130 documents a separate stale-process failure with an approximately `842.733 s` hidden JVM-age prefix. A JVM inherited from preparation is invalid evidence even if later Minecraft phases look normal.
- The follow-up low-noise physical sample reached `main_menu_opening` at `355,582 ms` and `main_menu_presented` at `361,195 ms`, with `70/70` listener lifecycle rows. The diagnostic build did not complete its intended auto-exit; the process was force-stopped only after the presented marker and rows existed.
- PR #113 already records the Windows-specific reason not to equate `Minecraft.stop()` with process exit: that diagnostic used benchmark-only `System.exit(0)` because an isolated Windows native-loop stop hang had been observed.
- Integration now explicitly requires Prism to be stopped before editing `instance.cfg`, effective command-line verification on the next launch, and exactly one BootOptim JAR in `mods/`.

These are harness failures, not startup-performance findings. They must be rejected or classified separately before any timing comparison.

## Four independent state authorities

The controller must distinguish all four:

1. **Disk configuration** — the bytes and hash of `instance.cfg`, `options.txt`, and the staged JAR set.
2. **Prism in-memory configuration** — a running Prism process can retain settings and later serialize them, overwriting an external edit.
3. **Launch identity** — the exact Prism executable, Prism application root, instance ID, interactive Windows principal, and scheduled-task action used for the run.
4. **Effective JVM state** — the actual `java.exe`/`javaw.exe` process command line, executable path, PID, and creation time.

Only state 4 proves which JVM properties were used by the measured process. Prism's source registers instance `JvmArgs` as an override of global `JvmArgs` controlled by `OverrideJavaArgs`; a harness that edits instance-local JVM arguments must therefore assert the override is active rather than assuming the text field wins.

## State machine

A run is represented by one durable JSON state file and advances only through these states:

```text
RECOVERED
  -> QUIESCENT
  -> SNAPSHOTTED
  -> STAGED
  -> TASK_READY
  -> LAUNCH_REQUESTED
  -> JAVA_RESOLVED
  -> TIMING
  -> PROCESS_ENDED | WATCHDOG_KILLED
  -> COLLECTED
  -> VALIDATED
  -> RESTORED
  -> RECOVERED
```

Any failed invariant enters `QUARANTINED`. Recovery from `QUARANTINED` is explicit: prove Prism is stopped, prove the measured Java identity is no longer running, restore the original files from the snapshot, verify restoration hashes, and only then return to `RECOVERED`.

The state file itself should be updated atomically (`state.json.tmp` -> replace) and guarded by a single-run lock. Re-running the controller after interruption must resume or recover from the recorded state; it must not blindly stage again.

## Run manifest

Minimum durable fields:

```json
{
  "schema": 1,
  "run_id": "physical-...",
  "state": "STAGED",
  "authority": {
    "integration_sha": "2bccf5f4fa221c78e286d052beb78636fa4c317b",
    "measurement_origin": "physical_laptop",
    "endpoint_contract": "main_menu"
  },
  "prism": {
    "exe": "<absolute path>",
    "exe_sha256": "<sha256>",
    "root": "<application root>",
    "instance_id": "<folder id>",
    "interactive_user": "<DOMAIN\\user>"
  },
  "snapshot": {
    "instance_cfg_sha256": "...",
    "options_sha256": "...",
    "mods": [{"name": "...", "sha256": "..."}]
  },
  "staged": {
    "instance_cfg_sha256": "...",
    "bootoptim_jar": {"name": "...", "sha256": "..."},
    "required_jvm_tokens": ["-Dboot_optim....=true"],
    "forbidden_jvm_tokens": []
  },
  "process": {
    "pid": null,
    "creation_date": null,
    "exe": null,
    "command_line_sha256": null,
    "forced_kill": false
  },
  "artifacts": {},
  "validation": {}
}
```

Do not put secrets in this file. Store hashes and identity/provenance, not credentials.

## Idempotent protocol

### 1. Recover and quiesce

Before touching instance files:

- stop every Prism process belonging to the target application root and verify it is gone;
- enumerate `java.exe` and `javaw.exe` via `Win32_Process`, recording PID, `CreationDate`, `ExecutablePath`, `ParentProcessId`, and `CommandLine`;
- reject the run if any existing JVM matches the target instance/game directory or other exact provenance token;
- create the run lock and `RECOVERED -> QUIESCENT` transition only after those checks pass.

Never kill all Java processes globally. Cleanup is permitted only for a process whose instance provenance and PID/creation pair match the run manifest.

### 2. Snapshot before modification

Copy the original `instance.cfg` and any mutable benchmark files byte-for-byte into the run recovery directory. Record SHA-256 for each. Record the full `mods` manifest before changing it. This snapshot is the restoration authority; a later Prism-written file is not.

### 3. Stage while Prism is stopped

Edit `instance.cfg` only in `QUIESCENT` state. Requirements:

- the target instance ID and Prism root are exact;
- `OverrideJavaArgs=true` when instance-local `JvmArgs` are the mechanism used to select benchmark properties;
- exactly one effective `JvmArgs` key is present;
- each required `-Dboot_optim.*` property appears exactly once with the intended value;
- stale candidate properties are absent unless explicitly part of the run contract;
- exactly one BootOptim distributable JAR remains in the instance `mods` directory and its SHA-256 matches the expected artifact;
- re-read and hash `instance.cfg` after the edit.

Immediately before launch, verify Prism is still absent and verify the staged config/JAR hashes again. If either hash changed, abort before starting the clock.

### 4. Prepare an interactive scheduled task

Use a task that runs as the already logged-in desktop user with an interactive token. The action should execute a small, fixed local launch script rather than embedding a long multi-layer quoted command directly in `/TR`.

The launch script should invoke the exact Prism executable with both a pinned application root and instance ID, equivalent to:

```text
prismlauncher.exe -d <exact-prism-root> -l <exact-instance-id>
```

Persist/query the task definition before use and verify the principal and action. Task Scheduler's `/IT` contract is meaningful only when the `/RU` user is logged on. `schtasks /run` starts the saved task immediately, but task state is not Minecraft-JVM state.

For SSH transport of PowerShell orchestration, prefer `powershell.exe -EncodedCommand <base64>` using UTF-16LE before Base64. This removes one remote quoting layer. Do not use encoded transport as a substitute for validating the local task action or effective JVM command line.

### 5. Launch and resolve exactly one Java process

Record the controller's launch wall-clock and monotonic timestamps, then run the task. During PID discovery, query process metadata only. Candidate Java processes must:

- be created after `LAUNCH_REQUESTED`;
- be `java.exe` or `javaw.exe` from the expected Java installation when that path is part of the fixed benchmark contract;
- contain unambiguous target-instance/game-directory provenance in their command line;
- contain every required JVM property exactly once with the required value;
- contain no forbidden/stale benchmark property;
- not match any preflight PID/creation fingerprint.

Exactly one process must match. Zero matches is launch failure; multiple matches is ambiguous provenance and invalid. Store PID + `CreationDate` + executable path + command-line SHA-256. Because Windows reuses PIDs, all later process operations must revalidate the PID/creation pair, not PID alone.

The effective command line is the final authority. A correct `instance.cfg` with a wrong command line is an invalid run.

### 6. Timing phase: process observation only

After `JAVA_RESOLVED`, the controller enters `TIMING`. Until the target process ends or the watchdog expires:

- do not open, tail, grep, copy, or stat-poll `latest.log`, `bootoptim-startup.log`, or the diagnostic console;
- do not run the offline parsers;
- do not inspect resource-pack logs to decide when the menu arrived;
- do not interact with the desktop or Prism;
- only check whether the exact PID/creation identity still exists and whether the external watchdog deadline has expired.

The external controller's wall time is an operational timeout, **not** the BootOptim TTMM measurement. TTMM comes from the in-JVM startup marker parsed later.

### 7. Endpoint and auto-close semantics

`main_menu` and `main_menu_presented` are different endpoints and never substitute for one another.

- Current production integration reports `main_menu` on accepted `TitleScreen` opening. A production benchmark contract may require only that marker.
- The PR #148 variance harness additionally reports `main_menu_presented` immediately after the first `Window.updateDisplay()` following title opening. A variance-harness contract requires both markers in order and the listener lifecycle rows required by its parser.

Auto-close is a separate outcome. `Minecraft.stop()` can be requested after a valid menu marker yet fail to terminate promptly on Windows. Therefore store `endpoint_valid` and `autoclose_ok` independently. If a diagnostic run reaches `main_menu_presented`, emits every required row, then needs an identity-checked forced kill, it may remain valid for phase attribution while `autoclose_ok=false`; it is not a clean voluntary-exit benchmark.

### 8. Watchdog

Use one generous process-level watchdog based on the established 3–5 minute laptop behavior plus explicit margin. The watchdog does not poll logs for progress. On timeout:

1. re-read `Win32_Process` and prove PID + creation + command-line provenance still match;
2. stop only that process (and proven descendants if necessary);
3. record `WATCHDOG_KILLED`, `forced_kill=true`, and the controller timestamp.

Do not treat `schtasks /end` as proof that the Java descendant terminated.

### 9. Collect once, offline

Only after the measured Java process is absent:

- copy the complete console if available, `latest.log`, `bootoptim-startup.log`, `options.txt`, and any harness report into the run artifact directory;
- hash the copied artifacts;
- run resource-selection validation and variance parsing **only on the frozen copies**;
- never parse a file that the measured JVM can still modify.

This matters on the laptop for both correctness and observer noise. Early ModLauncher/service-layer rows may precede normal `latest.log`; conversely, the variance listener table is intentionally emitted only after `main_menu_presented`. Reading at opening can therefore be simultaneously too late for the earliest evidence and too early for the final listener evidence.

### 10. Validate

A clean valid run requires all applicable invariants:

- fresh process provenance: no inherited target JVM; process creation after launch request; PID/creation identity consistent;
- effective command line matches the exact required/forbidden property contract;
- staged config and BootOptim JAR hashes match the recorded prelaunch state;
- exactly one active BootOptim JAR;
- required endpoint marker(s) exist exactly once and in order;
- no duplicated initial reload or missing mandatory variance boundary;
- exact resource-pack selection/order for every effective reload; no fallback;
- no BootOptim Mixin failure;
- for the variance harness, listener lifecycle coverage is complete and structurally valid;
- inclusive async/listener durations are kept as overlapping observations and are never summed as savings.

Validity should be stored as a vector, not one lossy Boolean, for example `endpoint_valid`, `process_fresh`, `effective_config_valid`, `resource_contract_valid`, `listener_contract_valid`, `autoclose_ok`, and `restoration_ok`.

### 11. Restore safely

Restoration must also happen with Prism stopped. This is critical: restoring `instance.cfg` while a Prism process still owns staged settings can be undone when Prism later saves its in-memory copy.

- verify/stop Prism;
- restore the original `instance.cfg` bytes from the snapshot;
- restore the original BootOptim/mods manifest exactly;
- remove diagnostic-only properties/artifacts;
- verify every restoration SHA-256;
- delete the temporary task and launch script if they are run-scoped;
- leave Prism stopped unless a later operator action intentionally opens it.

Only then set `RESTORED -> RECOVERED` and release the run lock.

## Failure classification

Use explicit reasons instead of trying to salvage timings:

- `stale_prism_config`: staged config hash changed, Prism was active during edit/restore, or effective command line does not match staged properties;
- `inherited_jvm`: matching target JVM existed preflight or creation predates launch request;
- `ambiguous_jvm`: more than one new matching Java process;
- `wrong_instance_or_root`: Java provenance does not match the pinned instance/root;
- `jar_contract_failed`: zero/multiple BootOptim JARs or wrong artifact hash;
- `endpoint_missing` / `endpoint_duplicate` / `endpoint_order_invalid`;
- `autoclose_failed`: required endpoint exists but target process did not terminate voluntarily within the post-endpoint contract;
- `resource_contract_failed`: options/reload selection differs or fallback occurred;
- `listener_contract_failed`: missing/malformed lifecycle rows for a variance run;
- `watchdog_timeout`: no valid endpoint can be inferred until offline artifacts are checked, and a timeout alone is never a timing result;
- `restoration_failed`: original byte/hash state was not recovered.

## Physical-run policy

Do not start a large campaign merely to validate the controller. The current evidence already includes a corrected production run at `350,330 ms` and a low-noise instrumented run at `355,582 ms` opening / `361,195 ms` presented with complete listener coverage. The next useful hardware action, if the controller itself changes, is at most one fixed-setting protocol-certification launch. It should prove state/provenance/restore correctness, not attempt to estimate a new performance distribution.

The performance investigation remains separate: physical variance is currently dominated by the resource/model pipeline, especially the ModelBakery/load/bake branch, and the established listener measurements are inclusive/overlapping. Harness hardening does not turn those observations into additive savings or justify an optimization by itself.

## Related evidence

PRs #113, #130, #148, #150, #156 and #157; `docs/research/p0.2-variance-harness-2026-09-06.md`; `docs/research/modelmanager-physical-variance-2026-09-06.md`; and `docs/research/exact-pack-ci.md`.

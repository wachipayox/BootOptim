# P2 null-audio run — clean-exit causality gap — 2026-09-07

Status: **DIAGNOSTIC / EXIT CAUSE INSUFFICIENT / NO RUNTIME CHANGE**

Authority at branch creation: `agent/integration-current` `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## New physical observation

A later causal run used Oracle Java 21.0.9, the #169 diagnostic JAR, `mcefFirstConsumerDefer=true`, `mcefNativeBootstrapProbe=true`, the imported world `realmente lo hizo_`, `exitOnTitle=false`, and valid Prism environment JSON `Env={"ALSOFT_DRIVERS":"null"}`. The effective client state reached OpenAL `No Output`, so this run actually exercised the null backend.

The hardened #172 observer completed schema 3 for Java PID 5200 (creation `2026-09-07T22:34:16.2980690+02:00`) and reported exit code 0 at about 22:45:07 with no matching Application Error/WER 1000/1001. The runtime log reached a real #90 first consumer and successful MCEF initialization (`consumer:fancymenu_video_render`, `result=true`, `wall_ms=63118.762`), observed multiple `jcef_helper.exe` processes, and emitted `BootOptimBench joined the game`.

The user nevertheless observed the game/window close during world entry. Exit code 0 and no WER are therefore **not** classified as a successful gameplay/world-entry run.

## What this does and does not establish

- It does establish a valid null-audio execution: unlike `p2-native-exit-20260907a`, the null-backend premise was satisfied.
- It establishes that real MCEF/CEF initialization completed in this execution. That removes the earlier `before first consumer` classification, but it does not make CEF causal for the later visible close.
- `BootOptimBench joined the game` proves the logical client connection/event boundary was reached. It does **not** prove that terrain rendering finished, that the LevelLoadingScreen was dismissed, or that a stable in-world frame was presented.
- Parent exit code 0 plus no WER rules out the same observed parent BEX64 signature from this run. It does **not** prove an intentional Minecraft shutdown: `TerminateProcess` can be invoked with code 0, a launcher/controller can close/kill a process, and a child/helper can fail independently while the Java parent later exits cleanly.

## Public-tooling audit

The script named `p2-causal-run.ps1` is not present in the public BootOptim repository or PR #172. Its concrete `Stop-Process`, `CloseMainWindow`, timeout, scheduled-task and Prism cleanup behavior therefore cannot be audited from GitHub. Treat controller-origin closure as **unresolved**, not disproven.

PR #172 also has two concrete observer blind spots for this question:

1. it polls only `Win32_Process ParentProcessId=$JavaPid`, so it can miss grandchildren/descendants created by a `jcef_helper.exe` browser process;
2. it checks known child exit state only after the Java parent exits. A helper may fail materially earlier, and the timeline does not say when that happened relative to the visible close.

Its parent exit code therefore describes only the validated Java process. It is not a process-tree health verdict.

## Diagnostic continuation

`remote_laptop_exit_causality_probe.ps1` is external/read-only diagnostic tooling. It does not modify Minecraft, MCEF, CEF, GPU, audio, Java or gameplay. It:

- binds the Java parent by PID + Win32 creation identity;
- optionally binds the exact Prism process by PID + creation identity;
- recursively discovers descendants from Java and already-known descendants;
- opens process handles while descendants are alive, persists `process_seen` and `process_exit` events, and records signed/hex exit code even if a helper dies while Java remains alive;
- records sanitized CEF `--type=` and process kind, not full command lines;
- records changes to visible top-level Java windows and Prism window/process state;
- optionally ingests a controller JSONL event ledger after exit, so a controller can prove that it invoked `CloseMainWindow`, `Stop-Process`, timeout cleanup or another explicit action instead of relying on inference;
- optionally reads specified logs only after Java exits and records presence of `minecraft_stop`, `jvm_shutdown_hook`, `joined the game`, loading-screen markers, and the last MCEF/bootstrap marker;
- preserves relevant Application Error/WER 1000/1001 evidence;
- emits a conservative classification. `parent_zero_without_shutdown_provenance` is intentionally **not** called success or graceful shutdown.

The observer itself does not and cannot identify the caller of an arbitrary external `TerminateProcess`. Windows does not expose that provenance through `Process.ExitCode`. A controller event ledger or a suitable OS process/audit trace is required to prove who requested termination.

## World-entry evidence gate

For this front, `joined the game` is an intermediate marker. A future run should distinguish at least:

1. `Loading terrain` / `level_loading_screen` entered;
2. logical join marker;
3. loading screen removed / normal gameplay screen active;
4. at least one subsequent stable presented frame/window still visible.

The existing #169 runtime diagnostic does not provide all four boundaries, so this branch does not change runtime solely to add them.

## Classification of the new physical run

Current classification: **EVIDENCE INSUFFICIENT FOR EXIT CAUSE**.

- same OpenAL parent crash as first run: **not observed** (null backend active, parent exit 0, no WER);
- Java parent crash: **not evidenced**;
- clean Minecraft-requested shutdown: **not proven** because the prompt does not establish `minecraft_stop` + `jvm_shutdown_hook` before exit;
- external/controller/Prism termination: **possible, unproven** because `p2-causal-run.ps1` is unavailable publicly and no controller-action ledger was preserved;
- helper CEF/GPU failure with later clean Java exit: **possible, unproven** because #172 did not recursively retain helper descendant exit timing/codes;
- stable visual world entry: **not proven** by `joined the game` alone.

## Exact missing evidence

Do not start another campaign. If one final causal run is ever authorized, the unique missing datum is an **exit-provenance tuple** for the same visible closure:

- whether `minecraft_stop` and `jvm_shutdown_hook` occurred before Java disappearance;
- every observed JCEF/CEF descendant's creation/exit time and exit code;
- Java and Prism visible-window/process transitions;
- an explicit controller ledger proving whether the harness invoked any close/kill/timeout action;
- WER/`hs_err_pid*`/Minecraft crash-report presence;
- a post-loading-screen visual/present marker, not only `joined the game`.

With those together, exit 0 can be separated into Minecraft clean shutdown, external/controller termination, helper failure followed by parent shutdown, or still-insufficient evidence. Until then, no user-facing audio/GPU/MCEF/Java change is justified.

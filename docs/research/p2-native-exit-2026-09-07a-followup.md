# P2 native-exit physical follow-up `p2-native-exit-20260907a` — 2026-09-07

Status: **DIAGNOSTIC / OPENAL STOCK FAULT OBSERVED / NULL-AUDIO CAUSAL PASS NOT YET RUN / NO PRODUCTION FIX**

Authority at branch creation: `agent/integration-current` `da9ffb15c13e1fa521a2e332f9b8cf8a6e87fa01`. This continuation is tooling/documentation only and does not change audio, GPU, MCEF, CEF, Java, rendering or gameplay behavior.

## Artifact and requested runtime

The physical run used the PR #169 packaged `bootoptim-ci.jar` with SHA-256:

`0A469AC64DB5842F691A6A1FF2D574B3AA599D7A4AEFDA7286B7DBCF2871B4F7`

The Java process was Oracle Java 21.0.9, PID `828`, Win32 creation identity `2026-09-07T21:31:27.1461220+02:00`. The effective BootOptim diagnostic configuration included `-Dboot_optim.mcefNativeBootstrapProbe=true`, production #90 first-consumer defer enabled, and `exitOnTitle=false`.

## Three independent facts from the run

### 1. Physical native termination classification

`latest.log` reached BootOptim's menu summary with `total_startup_ms=388029` and later began the integrated server at 21:43:04. Before the process disappeared, the only relevant BootOptim/MCEF lifecycle markers were:

- `BOOTOPTIM_NATIVE_EXIT_PROBE stage=installed`;
- `BOOTOPTIM_MCEF_FIRST_CONSUMER status=deferred`.

The log contains **no** `BOOTOPTIM_MCEF_FIRST_CONSUMER status=initializing`, no `BOOTOPTIM_MCEF_NATIVE_PROBE` bootstrap stage, and no `Chromium Embedded Framework initialized` marker.

Windows then recorded Application Error/WER 1000/1001 for that same Java PID (`0x33c` = 828):

- process: `javaw.exe` 21.0.9.0;
- module: `C:\Users\wachi\AppData\Local\Temp\lwjgl_wachi\3.3.3+5\x64\OpenAL.dll` 1.23.1.0;
- exception: `0xc0000409`;
- offset: `0xA2B05`;
- WER classification: `BEX64`.

**Causal conclusion for this execution:** the concrete observed termination is the recurrent stock LWJGL OpenAL native fault, and it occurred before BootOptim observed the first real MCEF consumer. The generic MCEF temporal-nearness hypothesis is not supported here. This run provides no evidence that `CefUtil.init`, JCEF DLL loading, `CefInitialize`, a CEF GPU helper or browser creation caused the parent termination.

This remains a fault-site classification, not proof that OpenAL alone is sufficient under every pack state. It also does not erase earlier evidence that other native surfaces can fail under different isolation experiments.

### 2. The intended null-audio isolation did not happen

The Prism instance contained:

`Env={ALSOFT_DRIVERS:null}`

That is not valid JSON for Prism's environment map. The effective client log instead says:

`OpenAL initialized on device OpenAL Soft on Altavoces (Realtek High Definition Audio)`

Therefore `p2-native-exit-20260907a` is a **normal-device/stock-OpenAL run**, not an `ALSOFT_DRIVERS=null` run. It cannot answer whether the same world-entry interval survives with the null backend.

The corrected Prism value for any later single causal pass is:

`Env={"ALSOFT_DRIVERS":"null"}`

The existing P2 methodology still applies: Prism must be stopped before editing `instance.cfg`, the fresh Prism process must load the edited setting, and the effective OpenAL line must prove the environment reached Java. The acceptance marker for real null isolation is `OpenAL initialized on device No Output`; the JSON text alone is not evidence that the backend was selected.

### 3. Transaction runner invalidation is separate from the native fault

The transactional runner waited a fixed 90 seconds for a target Java process, found none, and marked the transaction invalid. Prism started Java only later. The eventual Java process was identified independently by its full effective command and creation identity, and the WER PID matches it.

This means:

- the Windows/OpenAL crash evidence remains useful as a native diagnostic because PID/module/exception identity is concrete;
- the transaction is still invalid as a clean benchmark transaction because the runner did not own/validate the Java process on its normal state-machine path;
- `total_startup_ms=388029` is recorded only as the process-relative BootOptim startup-summary value seen in this diagnostic run. It is **not accepted as a comparable TTMM result or A/B performance sample**.

The 90-second discovery miss is a harness/orchestration issue, not evidence about Minecraft startup performance and not a cause of the later BEX64.

## Why `native-exit.json` could disappear with the #169 observer

The #169 observer attached by validated PID + creation identity, but no `native-exit.json` was recovered after the process closed. There is not enough surviving evidence to identify the exact observer exception and this document deliberately does not invent one.

The script nevertheless had a demonstrable evidence-loss design flaw: its only `Save(...)` occurred at the end of the success path. Initial `Get-CimInstance`, Java identity conversion/comparison and `Process.GetProcessById` happened before the main `try`; later `WaitForExit`, parent exit-code access, checkpoint serialization and atomic move could also throw before the final save. Any one of those failures could produce exactly zero result files. A BEX64 process exit should normally remain waitable through a Windows process handle, but the old observer gave no way to distinguish an attach race, process-handle error, I/O/serialization failure or another observer exception.

The continuation hardens this failure class rather than claiming to reproduce the physical observer failure.

## Observer hardening in the continuation

`remote_laptop_native_exit_probe.ps1` now uses schema 3 and:

1. writes a `starting` primary checkpoint **before** process lookup;
2. moves identity query and process-handle attach inside the top-level guarded path;
3. writes an `attached` checkpoint after PID + `CreationDate` validation;
4. treats child-process CIM polling failures as warnings instead of losing the observer;
5. checkpoints `parent_exited` immediately when the parent disappears, before child exit inspection or WER grace;
6. makes parent/child exit-code collection best-effort so an unavailable exit code does not discard the rest of the evidence;
7. writes the final `complete` result after the delayed WER query;
8. on every caught fatal observer error, attempts to write the partial primary result with `status=observer_error` **and** a dedicated `$OutputFile.observer-error.json` sidecar containing stage, exception type/message/category and script stack trace;
9. if the requested sidecar path itself is unavailable, attempts a second sidecar under `%TEMP%` named `bootoptim-native-exit-probe-<javaPid>-<observerPid>.observer-error.json` and prints that path to stderr.

No script can guarantee a sidecar if the observer PowerShell process itself is forcibly killed, the machine loses power, or both requested and `%TEMP%` storage are unavailable. The contract is therefore: **every caught observer failure is dual-path recoverable; abrupt target-Java termination must not by itself prevent finalization.**

## Hosted Windows proof for the hardening

`tools/laptop-bench/test_remote_laptop_native_exit_probe.ps1` supplies two deterministic cases on `windows-latest`:

- launch a real Java 21 fixture, attach the observer by CIM PID/creation identity, forcibly terminate the target Java process, then require a schema-3 primary result with `status=complete` and `exitedUtc`;
- launch another Java fixture, deliberately supply a wrong creation identity, then require both a primary partial result with `status=observer_error` and the preferred `.observer-error.json` sidecar at stage `identity_query`.

This does **not** reproduce Windows BEX64 or the laptop's OpenAL crash. It proves the observer's recovery behavior for an abrupt target-process death and a caught fatal observer error without modifying the Minecraft runtime.

## Exact missing datum for one future causal pass

No campaign is justified. At most **one** later pass is useful because two material premises have now changed from `p2-native-exit-20260907a`: Prism's environment JSON is corrected and the observer can preserve partial/error state.

That pass is causal only if all of the following are true:

1. fresh Prism loads `Env={"ALSOFT_DRIVERS":"null"}` and the client log proves it with `OpenAL initialized on device No Output`;
2. the Java PID + creation identity are validated for the actual process before observer attachment;
3. the hardened observer leaves either a final schema-3 result or, on observer failure, the partial result plus recoverable sidecar;
4. the same world is entered and the process is observed through the previous failure window;
5. if the process terminates, preserve the last first-consumer/MCEF-native marker, Java exit code/hex when available, WER 1000/1001 and any JVM crash artifact.

The missing causal datum is therefore precise: **does the same world-entry interval still terminate when the effective OpenAL device is demonstrably `No Output`, and if it does, what are the last MCEF-consumer/bootstrap marker plus parent exit/Windows fault evidence?**

Until that datum exists, do not recommend CEF `--disable-gpu`, DLL substitution, a Java change, MCEF removal or any audio policy for users.

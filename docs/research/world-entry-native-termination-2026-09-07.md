# World-entry native termination boundary — 2026-09-07

Base authority refreshed immediately before branching: `agent/integration-current` at `da9ffb15c13e1fa521a2e332f9b8cf8a6e87fa01`.

Status: **DIAGNOSTIC / NO PRODUCTION FIX / SIN EVIDENCIA FÍSICA FOR THIS BRANCH**.

This branch deliberately does not disable audio, MCEF, CEF GPU acceleration, WebDisplays, FancyMenu, world rendering, or any gameplay path. It adds a default-off causal probe and a separate high-noise native-exit observer. Neither is promotion evidence.

## Why the prompt's MCEF log boundary is not yet causal

The exact pack uses MCEF `2.1.6-1.21.1`. Upstream `CefInitMixin` logs:

1. `MCEF already finished downloading, scheduling loading.`
2. `MCEF is attempting to load.`
3. sleeps one second;
4. calls `MCEF.initialize()`.

BootOptim production PR #90 is integrated and injects at `MCEF.initialize()` HEAD. Its automatic-init path is intentionally suppressed until the first guarded browser consumer. Therefore `MCEF is attempting to load` is **not proof that JCEF/CEF native bootstrap began** while #90 is enabled. The first real native attempt is instead preceded by:

`BOOTOPTIM_MCEF_FIRST_CONSUMER status=initializing consumer=...`

Only after that marker does BootOptim call the authoritative real `MCEF.initialize()` on the client thread.

PR #144 changes owner/waiter/re-entry coordination around this forcing path. The supplied physical evidence says the complete closure persisted under #144, so this branch does not repeat or promote that state-machine change.

## Exact upstream native boundary

MCEF `MCEF.initialize()` calls `CefUtil.init()`. On success it then creates MCEF wrapper/client objects and logs `Chromium Embedded Framework initialized`. On a normal `false` return it dispatches failure callbacks, logs an error, calls `shutdown()`, and returns `false`; it does not deliberately terminate the JVM.

At the exact MCEF 1.21.1 source, `CefUtil.init()` performs:

1. `CefApp.startup(cefSwitches)`;
2. `CefApp.getInstance(cefSwitches, cefSettings)`;
3. `cefAppInstance.createClient()`.

The exact `CinemaMod/java-cef` submodule is commit `eaeb3d4370aa3526ee237ad1981ad59af3de4dd1`. On Windows:

- `CefApp.startup(...)` reaches `N_Startup`, whose Windows path simply returns success after the library is already available;
- `CefApp.getInstance(...)` constructs `CefApp` and calls `N_PreInitialize`, which records JVM/class-loader state;
- the first `createClient()` calls Java `initialize()`, sets `browser_subprocess_path` to `jcef_helper.exe`, then enters `N_Initialize`;
- native `Context::Initialize` calls `CefInitialize(...)`, where Chromium browser-process initialization and helper-process creation happen.

No `System.exit`, `Runtime.halt`, or Java-level intentional parent termination was found in this exact MCEF/JCEF bootstrap path. That does **not** prove CEF is innocent: a fail-fast/native exception inside `CefInitialize`, a driver fault, or an unrelated concurrent client termination can still kill the parent without returning to Java.

## Evidence separated by subsystem

### WER / OpenAL

Physical evidence establishes a real failure site, not a complete root cause:

- stock LWJGL `OpenAL.dll` 1.23.1.0 has repeatedly faulted in `javaw.exe` with `0xc0000409`, including after the Realtek update;
- explicit OpenAL Soft reached CEF and later failed in `soft_oal.dll` with `0x40000015`;
- an isolated LWJGL OpenAL harness does not reproduce the full-pack closure.

A null OpenAL backend removes the observed OpenAL WER signature but the full client can still disappear. Therefore audio remains a confirmed native crash surface, while **absence of a new OpenAL WER is not resolution** and OpenAL cannot currently explain every closure.

### MCEF / CEF

The null-audio run disappearing around MCEF messages is suggestive but the two generic MCEF messages precede #90's suppression boundary. The missing discriminator is whether the run reached the first real consumer and, if so, whether it died in `getInstance`, `createClient/CefInitialize`, or after `CefUtil.init` returned.

This branch adds exactly those markers, default-off and guarded to MCEF `2.1.6-1.21.1`.

### GPU / render backend

CEF enables windowless rendering in this pack and helper processes may include `--type=gpu-process`. MCEF source itself contains a note about `--disable-gpu` as a possible workaround for white-screen systems, but that comment is not evidence for this termination. No production GPU switch is changed here. OpenGL/world rendering also remains unexonerated because a concurrent driver/native failure can terminate the client while MCEF happens to be logging.

The probe records only sanitized helper identity (`jcef_helper`-style executable basename plus `--type=`) and does not move any render/OpenGL work off the client thread.

### Java

The supplied null-audio closure reproduced on Java 25.0.4 and JDK 21.0.9. MCEF 2.1.6-1.21.1 is built for Java 21, so Java 21 is the appropriate supported baseline for the next diagnostic run, but switching to Java 21 is **not** a fix and is not evidence that the native problem is resolved.

## Diagnostic added by this branch

### In-process exact bootstrap markers

Enable only for a forensic run:

`-Dboot_optim.mcefNativeBootstrapProbe=true`

Default is `false`. On an exact MCEF version mismatch the probe fails open and logs that it disabled itself. Mixin injection points use `require=0`.

Expected ordered markers are:

- `stage=cefutil_head`
- `stage=before_startup`
- `stage=after_startup`
- `stage=before_get_instance`
- `stage=after_get_instance`
- `stage=before_create_client`
- `stage=after_create_client`
- `stage=cefutil_return result=...`

A daemon watcher active only while `CefUtil.init()` is executing reports changes in direct child processes. It records PID, executable basename and only the sanitized `--type=` value. It does not persist full child command lines.

### External native-exit observer

`tools/laptop-bench/remote_laptop_native_exit_probe.ps1` attaches to an already-identity-validated Java PID + `CreationDate`, then polls direct child processes every 100 ms until the parent exits. It records:

- signed Java exit code and its exact 32-bit hex form;
- observed direct child processes, sanitized CEF `--type=`, and child exit code when available;
- recent Windows Application events 1000/1001 whose messages mention Java/JCEF/libcef/OpenAL/soft_oal.

This is intentionally a noisy diagnostic observer. Its JSON says so explicitly. **Do not use a run with this observer for clean TTMM/startup-performance claims.**

## Decision for the next physical run

Run **one** causal classification pass, not an A/B campaign:

- keep JDK 21.0.9 as the supported baseline;
- use the same exact pack/world and the same BootOptim artifact from this diagnostic PR;
- use `ALSOFT_DRIVERS=null` for this one run only to remove the already-confirmed OpenAL-device path as a confounder; this is diagnostic isolation, never a proposed default;
- add `-Dboot_optim.mcefNativeBootstrapProbe=true` exactly once;
- after the transaction has validated the target Java PID/CreationDate, launch `remote_laptop_native_exit_probe.ps1` against that identity and let it block until the client exits;
- enter the known world and preserve `latest.log`, the native-exit JSON, any Java crash artifact, and relevant WER entries;
- do not infer startup performance from this run.

Classify the result before making another change:

1. **No `BOOTOPTIM_MCEF_FIRST_CONSUMER status=initializing`:** the parent died before the first real #90 consumer. The two upstream `MCEF is attempting to load` messages were temporal coincidence for that run; do not patch CEF bootstrap.
2. **First-consumer marker, then no `cefutil_head`:** failure/re-entry lies between BootOptim forcing and entering MCEF `CefUtil`; inspect Java exception/log boundary rather than GPU.
3. **`before_get_instance` without `after_get_instance`:** failure lies in JCEF `CefApp` construction / `N_PreInitialize` / native-library boundary.
4. **`before_create_client` without `after_create_client`:** strongest boundary is `CefInitialize` itself. Cross-check whether a `jcef_helper`/`gpu-process` child appeared and the parent/child exit codes before testing any GPU switch.
5. **`after_create_client` / `cefutil_return` appears:** bootstrap completed. Move the investigation to first browser creation, renderer/GPU child lifecycle, WebDisplays/FancyMenu consumer behavior, or a concurrent OpenGL/native path; do not call MCEF initialization the root cause.
6. **Parent exit hex is `0xC0000409` or WER names OpenAL/soft_oal:** retain the native fault classification even if CEF markers are nearby. A nearby CEF marker does not overwrite a concrete faulting module/NTSTATUS.
7. **Clean exit code / no WER:** this still does not prove graceful Java shutdown. Inspect the final Minecraft/window markers and child lifecycle; absence of WER is not a pass.

Only if case 4 is reproduced with null audio and no OpenAL WER should the next cheapest hypothesis be a **separate diagnostic** CEF GPU-off run. That flag must remain opt-in and cannot be promoted without gameplay/visual equivalence and physical evidence.

## Risks

- The in-process child watcher wakes every 100 ms during `CefUtil.init`; observer effect is deliberate and disqualifies timing conclusions.
- Windows may deny some `ProcessHandle.Info` fields; the probe degrades to `unknown` rather than changing behavior.
- A native fail-fast can terminate the parent before buffered external/native logs flush. The ordered last marker is therefore a boundary, not a stack trace.
- Direct-child observation can miss a very short-lived helper or a grandchild. WER/exit code remain independent evidence.
- Hosted exact-pack can validate build/startup compatibility but cannot validate this Windows native/GPU/audio failure. Until the laptop reproduces with this branch: **sin evidencia física**.

## Public source anchors

- BootOptim PR #90: first-consumer MCEF defer.
- BootOptim PR #144: owner/waiter/re-entry hardening; not repeated here.
- BootOptim PR #163: exact-pack observer deadlock diagnostic fix.
- BootOptim PR #166 and integrated remote tooling: physical transaction/measurement support.
- MCEF `1.21.1`, version `2.1.6-1.21.1`: `MCEF.java`, `CefUtil.java`, `CefInitMixin.java`.
- CinemaMod/java-cef exact submodule `eaeb3d4370aa3526ee237ad1981ad59af3de4dd1`: `CefApp.java`, `native/CefApp.cpp`, `native/context.cpp`, `native/jcef_helper.cpp`.

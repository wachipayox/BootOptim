# P2 WebDisplays / OpenAL native world-entry crash — 2026-09-06

Status: **PROFILED / NATIVE FAILURE SIGNATURE CONFIRMED; ROOT CAUSE UNCONFIRMED**

Baseline: `agent/integration-current` @ `b0aa2472d58e3afc56a380e026c99ffe87000f22`.

Scope: investigate the recurrent Windows native process termination observed while entering a new world on the old project laptop. This note deliberately separates the **faulting native module**, a **trigger that changes timing or audio-device activity**, and the **root defect that produces the fail-fast**. It does not attribute the crash to BootOptim, MCEF or WebDisplays merely because their log activity can be nearby.

## Supplied physical evidence

Across multiple physical executions, Windows Application Error / Windows Error Reporting events 1000/1001 identify:

- application: `javaw.exe`;
- faulting module: `C:\Users\wachi\AppData\Local\Temp\lwjgl_wachi\3.3.3+5\x64\OpenAL.dll`;
- exception: `0xc0000409`, reported as BEX64;
- environment: Minecraft / NeoForge 1.21.1, Java 25.0.4, LWJGL 3.3.3, old Intel HD 3000-era hardware with the software/compatibility renderer path described in the physical notes;
- in some runs MCEF/WebDisplays and audio activity are close in wall-clock time, but no native stack or fast-fail subcode currently links those components to the fault.

A plain process exit without a matching Windows 1000/1001 event is **not** classified as this crash.

## Confirmed source facts

### The DLL path is the normal LWJGL bundled-native extraction path

LWJGL 3.3.3 ships OpenAL Soft **1.23.1**. LWJGL's shared-library loader extracts bundled natives by default under `java.io.tmpdir/lwjgl_<trimmed user>/<LWJGL version>/<architecture>/...`, exactly matching the observed `Temp\lwjgl_wachi\3.3.3+5\x64\OpenAL.dll` shape.

Sources:

- LWJGL 3.3.3 release notes: https://github.com/LWJGL/lwjgl3/blob/master/doc/notes/3.3.3.md
- LWJGL shared-library loader: https://github.com/LWJGL/lwjgl3/blob/master/modules/lwjgl/core/src/main/java/org/lwjgl/system/SharedLibraryLoader.java
- LWJGL native extraction configuration: https://github.com/LWJGL/lwjgl3/blob/master/modules/lwjgl/core/src/main/java/org/lwjgl/system/Configuration.java

Therefore the path itself is **not evidence of a stray system OpenAL installation or corruption**. The Windows event is pointing at the LWJGL-packaged OpenAL native image used by Minecraft.

### `0xc0000409` is fail-fast, not a self-interpreting stack-cookie verdict

Microsoft documents `0xc0000409` as `STATUS_FAIL_FAST_EXCEPTION`. Fail-fast bypasses normal exception handlers and terminates the process. Historically this code was introduced for `/GS` security-check failures, but it is now a generalized fail-fast mechanism; the first exception parameter is the subcode, and the original security-check case uses subcode zero.

Source: https://learn.microsoft.com/en-us/shows/inside/c0000409

Consequences for this investigation:

- `BEX64 + 0xc0000409` **does not by itself prove a stack-buffer overwrite or stack-cookie failure**;
- Java `try/catch` or a BootOptim fail-open wrapper cannot recover after this fail-fast fires;
- the missing discriminator is the fail-fast subcode plus a dump / faulting-thread stack (`.exr`, `!analyze` or equivalent WER dump analysis).

### OpenAL Soft 1.23.1 has relevant native-lifecycle history, but no exact match is proven

OpenAL Soft's changelog for 1.23.1 includes fixes for a potential crash when deleting an effect slot immediately after its last source stops and for WASAPI device handling. The next major line, 1.24.0, changed context error state to thread-local specifically to avoid races under multi-threaded use. These upstream changes establish that source/device/lifecycle concurrency is a legitimate native failure class; they do **not** establish that the laptop crash is one of those bugs.

Source: https://github.com/kcat/openal-soft/blob/master/ChangeLog

A separate upstream report (#1035) found rare Windows crashes in OpenAL Soft 1.23.1 built with Visual Studio 2022 `/MD`, around `alcIsExtensionPresent` / `alcOpenDevice`, while a differently built binary worked on the affected machine. The reporter lacked a stack trace and could not reproduce it generally. This is useful precedent for environment/build-specific OpenAL failure, not a fingerprint for this crash.

Source: https://github.com/kcat/openal-soft/issues/1035

No public issue located in this audit ties LWJGL 3.3.3 + Java 25.0.4 + the exact `0xc0000409` signature to one known OpenAL Soft defect.

### Java 25 is not a demonstrated root cause

JNI's primary design goal includes binary compatibility of native libraries across conforming JVM implementations on a platform. Oracle also explicitly notes that native/JNI code can exhibit undefined behavior and crash the process. That means Java 25 cannot be cleared absolutely, but a native OpenAL fail-fast does not become a Java-ABI fault merely because Java 25 is in use.

Source: https://docs.oracle.com/en/java/javase/25/docs/specs/jni/intro.html

No public evidence found in this audit demonstrates an LWJGL 3.3.3 OpenAL JNI calling-convention break specific to Java 25.0.4. Promote a Java/JNI hypothesis only if a dump/`hs_err` points into the JNI bridge/JVM or a checked-JNI diagnostic reports a concrete contract violation before the same failure.

### Minecraft audio has its own executor and world entry can create new native work without reloading OpenAL

A public NeoForge 1.21.1 thread dump shows Minecraft's `SoundEngineExecutor` as a dedicated thread. The sound engine's normal play path eventually schedules channel operations that attach buffers/streams and call `channel.play()`. Thus a crash first observed during world entry does not imply that OpenAL is first loaded there: world entry can simply be the first point that exercises a particular source/channel/device transition.

References:

- NeoForge 1.21.1 `SoundEngineExecutor` thread evidence: https://github.com/neoforged/NeoForge/discussions/2475
- representative SoundEngine patch showing reload and asynchronous play/channel continuation: https://github.com/MinecraftForge/MinecraftForge/blob/26.2/patches/minecraft/net/minecraft/client/sounds/SoundEngine.java.patch

The Forge patch is used only to illustrate the vanilla sound-engine structure; it is not treated as exact NeoForge 1.21.1 implementation proof.

### Exact MCEF / WebDisplays relationship

Current BootOptim evidence already establishes that MCEF `2.1.6-1.21.1` initializes synchronously on its caller and that exact WebDisplays `brother-bill/webdisplays-mc@df820aa136b64368a043c2c1e1ef2d0292d233eb` registers an MCEF init callback and creates browsers through `MCEF.getClient()`.

Exact WebDisplays source also shows browser instances being closed on block-entity unload/reload/clear paths. Browser creation/close can therefore alter CEF native activity around level lifecycle. However, no exact-source path found in this audit proves that WebDisplays routes CEF/Chromium audio through Minecraft's `OpenAL.dll`. The repository contains a `WDAudioSource` `SoundInstance`, but no call site was established at the pinned commit during this audit. CEF itself has its own browser-audio facilities.

Pinned sources:

- https://github.com/brother-bill/webdisplays-mc/blob/df820aa136b64368a043c2c1e1ef2d0292d233eb/src/main/java/net/montoyo/wd/client/ClientProxy.java
- https://github.com/brother-bill/webdisplays-mc/blob/df820aa136b64368a043c2c1e1ef2d0292d233eb/src/main/java/net/montoyo/wd/utilities/browser/WDBrowser.java
- https://github.com/brother-bill/webdisplays-mc/blob/df820aa136b64368a043c2c1e1ef2d0292d233eb/src/main/java/net/montoyo/wd/entity/ScreenBlockEntity.java
- https://github.com/brother-bill/webdisplays-mc/blob/df820aa136b64368a043c2c1e1ef2d0292d233eb/src/main/java/net/montoyo/wd/client/audio/WDAudioSource.java

Therefore MCEF/WebDisplays is currently a plausible **trigger/timing competitor**, not a demonstrated root cause of the OpenAL fail-fast.

## Causal grading

### Confirmed

1. The recurrent classified failure is a native Windows fail-fast with `OpenAL.dll` as the faulting module and exception code `0xc0000409`.
2. The observed DLL path is LWJGL's normal extracted bundled native path for 3.3.3, not evidence of an external system OpenAL DLL.
3. LWJGL 3.3.3 bundles OpenAL Soft 1.23.1.
4. `0xc0000409` alone is insufficient to identify the fast-fail reason; the first exception parameter/subcode or a dump is required.
5. A Java-level exception handler cannot safely recover from the already-raised fail-fast.
6. Existing MCEF first-consumer deferral changes when CEF starts, but does not replace Minecraft/OpenAL audio and is not an OpenAL fix.

### Probable / best current model

The strongest current model is **OpenAL Soft / Windows audio-device or source-lifecycle failure whose trigger is exposed around world-entry activity**. World entry is a natural point for new Minecraft sound sources, channel commands and device activity. CEF/WebDisplays may change timing, device concurrency or browser lifecycle and thereby expose the defect, but proximity is not ownership.

A stable repeated OpenAL module + offset/bucket across full-pack and no-MCEF/WebDisplays runs would substantially strengthen this model and make MCEF/WD unnecessary to reproduce.

### Unknown

- exact fast-fail subcode;
- faulting OpenAL function / thread;
- whether the fault occurs on Minecraft's sound executor, an OpenAL mixer/backend thread, Render thread, or another native thread;
- whether MCEF/WebDisplays is necessary, sufficient, merely timing-sensitive, or irrelevant;
- whether `mcefFirstConsumerDefer` changes only timing or changes crash incidence;
- whether the Windows audio backend/device state is part of the trigger;
- whether old-GPU/software-render CPU pressure merely changes race timing;
- any Java 25-specific involvement.

The Intel HD 3000 / Microsoft Basic Render Driver / Mesa compatibility environment is therefore treated only as a possible **indirect scheduling/load modifier**. The fault module does not justify a graphics-root-cause claim.

## Minimal physical isolation matrix

Do not run a broad A/B campaign. Use **three primary launches**, each from a clean launcher process and with the same new-world procedure. A fourth run is conditional only if the first three do not discriminate.

| Run | Pack / property | Purpose | Interpretation |
| --- | --- | --- | --- |
| **C0 control** | exact pack, normal current production setting `-Dboot_optim.mcefFirstConsumerDefer=true` | reproduce the classified failure under the current target state | establishes the reference WER signature and timing |
| **I1 no CEF consumers** | same pack except temporarily remove **both MCEF and WebDisplays** for this diagnostic launch; BootOptim unchanged | test whether CEF/WebDisplays is necessary | same OpenAL 1000/1001 signature => MCEF/WD not necessary; absence is only evidence of a possible trigger, not root-cause proof |
| **I2 eager MCEF** | exact pack restored, `-Dboot_optim.mcefFirstConsumerDefer=false` | separate deferred first-consumer timing from stock/eager MCEF lifecycle | same OpenAL signature => defer not necessary; only one setting failing => property/lifecycle becomes a trigger candidate, not an OpenAL root-cause verdict |
| **I3 conditional audio trace** | only if C0/I1/I2 are inconclusive: same exact pack plus a separately reviewed, default-off low-cardinality sound-lifecycle trace | correlate world-entry sound commands to WER without changing audio behavior | only justified after hosted build/smoke; one physical run, not another A/B series |

The MCEF/WebDisplays removal in I1 is an ephemeral isolation fixture, not a proposed user-facing configuration or mitigation. Do not change Java, Windows, drivers, OpenAL system files, audio backend configuration, renderer configuration or the user's permanent pack state.

## Evidence contract for every physical launch

Capture the complete Java process console/log through exit and export the **XML/detail view** of any matching Event 1000 and 1001, preserving:

- `TimeCreated` / report timestamp;
- process ID and application start time if present;
- application path/version;
- `OpenAL.dll` path/version/timestamp;
- exception code;
- **fault offset**;
- WER bucket / report ID;
- every exception parameter / fast-fail subcode exposed by WER;
- preserved `.dmp` / `Report.wer` path if Windows already generated one.

Correlate those wall timestamps with:

- new-world request / world-load boundary;
- MCEF defer armed/suppressed/first-consumer/initializer markers;
- first WebDisplays browser creation or close marker if one exists;
- last Minecraft sound-engine log/marker before termination;
- normal world-ready marker if reached.

Do not infer causality from log order alone. A log timestamp is wall ordering; a dump stack is ownership evidence.

### Hypothesis proof rules

**H-OpenAL intrinsic/lifecycle** is strongly supported if I1 still produces event 1000/1001 with the same OpenAL module and a stable fault offset/bucket. It becomes root-cause-grade only when a dump/subcode identifies the failing OpenAL path or an upstream exact-code match is established.

**H-MCEF/WebDisplays trigger** is supported only if the classified OpenAL failure requires MCEF/WD across the minimal matrix and its WER timestamp consistently follows a real CEF/WebDisplays lifecycle event. It remains a trigger until a native stack/data-flow connects that lifecycle to the failing OpenAL path.

**H-first-consumer-defer trigger** requires C0 versus I2 to change the classified failure while all other software is identical and the crash timing follows the changed MCEF state transition. Merely changing total wall time is insufficient.

**H-Minecraft world sound trigger** is supported if I1 still fails and the final pre-WER Java marker is a specific first-world sound/source/channel transition. A dump with the faulting thread in OpenAL source/channel/backend work would make this much stronger.

**H-Java/JNI** is reopened only by `hs_err`, dump frames, or a checked-JNI report showing a concrete JNI contract violation. Java version proximity is not enough.

A run that exits without a matching event 1000/1001 is recorded as **no classified OpenAL crash observed**, not as a different crash and not as proof of safety from one sample.

## Diagnostic branch decision

**Do not add a runtime diagnostic yet.** The three-run matrix plus existing WER metadata is lower-noise and has higher discriminatory power than adding another profiler before the faulting thread/subcode is known.

If I3 becomes necessary, the acceptable diagnostic is narrow and default-off: generation-scoped markers around world load/unload and the first few post-world `SoundEngine` source/channel transitions, with thread identity and monotonic time. It must not wrap every OpenAL call, poll logs, add sleeps, replace executors, suppress sounds, mute audio, or call native APIs from a new thread. It must pass build + hosted exact-pack smoke before one physical run and be reverted by removing the diagnostic mixins/helper/property.

## Safe mitigation analysis

### Safe now

- Keep the existing MCEF first-consumer design judged on its own startup evidence and use PR #144's owner/reentry hardening as a separate correctness gate. Neither should be sold as an OpenAL crash fix.
- Preserve stock Minecraft/OpenAL audio behavior while isolating. No sound suppression, forced mute, backend override, native DLL swap or device reset is justified.
- If the crash does not reproduce without MCEF/WebDisplays, use that only to focus the next trace on browser creation/close/audio-device concurrency.

### Not safe / not justified

- Catching the crash in Java: fail-fast bypasses normal handlers.
- Replacing LWJGL/OpenAL Soft in BootOptim or extracting a different `OpenAL.dll`: changes the native dependency and ABI/runtime surface and is outside this task's safe lane.
- Forcing DirectSound/WASAPI/null backends, altering Windows devices, drivers or Java: forbidden by the task and would confound the root-cause experiment.
- Permanently disabling WebDisplays, MCEF or Minecraft audio: gameplay/compatibility degradation, not an acceptable production mitigation.
- Adding broad synchronization around CEF or sound code without identifying the owner race: can hide timing while retaining the defect.

A future production mitigation inside a mod/JAR is promotable only if it avoids one proven unsafe lifecycle edge while retaining normal audio/browser semantics. For example, if a dump plus the minimal matrix proves a double-close or cross-thread close/create edge owned by a mod, a narrow idempotent owner-thread serialization guard could be considered. That is a **conditional design**, not a current candidate.

## Metrics: keep correctness separate from startup performance

- **CPU:** no CPU measurement currently explains the fail-fast. Existing process/thread CPU diagnostics can characterize load, but high or low CPU is not proof of an OpenAL defect.
- **wall:** current MCEF/WebDisplays/audio proximity is wall-clock correlation only. WER `TimeCreated`, log timestamps and monotonic markers are useful for ordering.
- **critical path:** this is a gameplay correctness failure after attempting world entry. A terminating process has no meaningful recoverable TTMM saving. Do not mix the previously measured MCEF startup savings with crash attribution.

## Promotion / closure / reopening criteria

### Close the MCEF/WebDisplays causal front

Close it as **not necessary to reproduce** if I1 produces the same classified OpenAL event signature (same module/code and materially stable offset/bucket) without MCEF and WebDisplays. Continue the investigation in Minecraft/OpenAL lifecycle instead.

### Promote a mod/JAR mitigation

Require all of:

1. a classified physical failure with complete 1000/1001 details and preferably a dump/subcode;
2. a specific source-level trigger edge tied to the failing native stack, not mere timestamp adjacency;
3. a narrow behavior-preserving mitigation with explicit owner/thread/lifetime invariants and fail-open behavior before the native call;
4. deterministic tests for the state/lifetime rule;
5. build + hosted exact-pack semantic smoke;
6. one focused physical reproduction showing the classified failure is removed while world audio and any affected WebDisplays/MCEF gameplay path still function;
7. no claim that one non-crashing run establishes a performance improvement.

### Close the overall front

Close as **environment/native root cause outside a safe BootOptim lane** if the failure is reproducible without optional CEF consumers, the dump identifies OpenAL Soft/backend-internal failure, and no behavior-preserving mod-owned lifecycle edge exists. Document the native defect; do not paper over it by disabling audio or swapping user/system dependencies.

### Reopen after an apparently clean matrix

Reopen only with a new matching 1000/1001 OpenAL fail-fast or a dump that adds a new discriminator. A normal process exit or unrelated crash module is a different incident.

## Missing physical evidence

The smallest missing set is:

1. XML/detail exports for one representative matching Event 1000 and its paired 1001, including **fault offset and exception parameters/subcode**;
2. a preserved WER dump or `Report.wer` if Windows already produced one;
3. C0/I1/I2 results with exact process/log timestamps and MCEF/WebDisplays state at termination;
4. whether the new world had any actual WebDisplays block/browser consumer before the fault;
5. the faulting thread / top native frames.

That evidence is enough to decide whether a diagnostic-only sound-lifecycle branch is necessary. No repeated physical performance A/B is requested.

## Related project history

- `docs/research/mcef-first-consumer-defer-2026-09-03.md`
- PR #129 owner-aware MCEF state contract
- PR #143 initial owner/reentry hardening
- PR #144 deterministic owner/reentry harness
- PR #148 low-noise physical variance harness (separate performance/variance diagnostic; do not repurpose its wall-minus-CPU data as crash causality)

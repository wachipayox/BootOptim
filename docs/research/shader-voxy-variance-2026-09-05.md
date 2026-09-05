# Shader / Voxy startup variance — 2026-09-05

Status: **ACTIVE DIAGNOSTIC / DO NOT MERGE AS PRODUCTION**

Base: `agent/integration-current` @ `d29a6bad6358c7ff78dadbc5e85bd753c0ad2a54`.

## Question

Four validated physical full-pack starts on the old Windows laptop vary by tens of seconds during reload/title. They all show the same six OpenGL shader-compiler debug messages and the same failed Voxy config replace. This front asks whether either is causal or merely a stable side effect of the renderer/resource path.

The physical renderer is `D3D12 (Microsoft Basic Render Driver)` with Mesa exposing OpenGL 4.2 compatibility. Hosted exact-pack CI uses Linux/Xvfb/llvmpipe and is **not GPU-equivalent**. Hosted is used only to validate observational instrumentation and exact-pack semantics; one fixed physical run is the only useful follow-up for the Windows/GL4.2 path.

## Source attribution

### First four GLSL failures: Flywheel 1.0.6 capability detection

The pinned hosted fixture reports Flywheel `1.0.6`. Public Flywheel source at the 1.0.6 release commit (`72af7b915a555a8e23f3c2638652a9f482a36deb`) defines `GlCompat.MAX_GLSL_VERSION = maxGlslVersion()` during static initialization.

`maxGlslVersion()` deliberately walks `GlslVersion.values()` from highest to lowest and calls `canCompileVersion(version)`. That helper creates a **minimal vertex shader** containing only the requested `#version` and `void main() {}`, compiles it once, checks status, deletes it, and continues downward only after failure. The enum order is 150, 330, 400, 410, 420, 430, 440, 450, 460.

Therefore a renderer whose highest accepted GLSL is 4.20 produces exactly the observed failed sequence **4.60, 4.50, 4.40, 4.30**, followed by a successful 4.20 probe that does not emit an error. These are four capability attempts, not four retries of a pack shader. The source contains no expensive fallback reconstruction at this point; wall/CPU still needs measurement before calling it free.

ThreatenGL is not the compiler. Its public mixin only changes GLFW context-version hints to request 4.6 on non-macOS and then calls `glfwWindowHint`; it contains no shader compile path. On the physical software renderer the request and the returned 4.2 context can coexist, which explains why Flywheel still has to probe the actual GLSL compiler.

Colorwheel is also not the first-group compiler: its OIT check only compares the renderer string against Apple. Accelerated Rendering does ship 4.60 compute shaders, but its reload loader compiles each selected shader once and throws on compile/link failure; its availability gate checks extensions and can skip loading. It does not implement the observed 4.60 -> 4.50 -> 4.40 -> 4.30 fallback sequence.

### Later two GLSL 4.30 / compute failures: Voxy capability probes are the source-level match

Public upstream Voxy `Capabilities` contains an explicit comment that int64 support is tested by compiling a shader. Its constructor calls `testShaderCompilesOk(COMPUTE, ...)` on a `#version 430` compute shader requiring `GL_ARB_gpu_shader_int64`; when `GL_KHR_shader_subgroup` is advertised it performs a second `#version 430` compute compile requiring subgroup extensions. `testShaderCompilesOk` creates, compiles, checks and deletes one shader; it does not retry or substitute a lower version.

That shape exactly matches the physical second pair: two GLSL 4.30/compute failures, much later than Flywheel and during client/resource initialization. However the exact fixture identifies a custom `Voxy Reforged ... 0.1.9-wedit` JAR. Public upstream is therefore **strong attribution evidence, not byte-for-byte proof**. The diagnostic instruments the same method signature if present and also observes Minecraft's existing GL shader-compiler callback so the one physical run can close the attribution without modifying Voxy.

Iris is not a good explanation for either hosted group: the pinned hosted fixture logs that shaders are disabled by `iris.properties`. Iris may still install compatibility machinery, but there is no active shaderpack compile in that hosted smoke. Veil compiles its own resources later; its direct compiler issues one compile per source rather than the Flywheel descending-version probe.

## Nine sampler/uniform warnings

The hosted llvmpipe smoke has OpenGL 4.6, emits **none** of the physical unsupported-version compiler errors, yet still emits the same nine later sampler/uniform warnings and reaches title. The warnings therefore are not a consequence of the GL4.2 version fallback.

Veil 4.1.4's `ShaderInstanceMixin.updateLocations` enumerates active uniforms. When `glGetUniformLocation` returns `-1`, it logs the warning, removes any stale Veil-side uniform object and continues. That warning branch does not compile, relink, or rebuild the shader. Vanilla `ShaderInstance` has analogous missing-location warnings for configured samplers/uniforms. The fixed warning count is not time evidence and no optimization is proposed from it.

## Voxy config save / AccessDeniedException

The exact physical logs show `VoxyConfig.save` failing while replacing `voxy-config.json.tmp` with `voxy-config.json` from deferred/resource-reload work. Public Voxy and public NeoForge ports inspected here currently write the config directly and do **not** expose that temp+move implementation; a public GitHub search also did not find `reloadAfterClientInitialization`. The exact `0.1.9-wedit` save implementation is therefore not source-auditable from the public sources available to this agent.

A temp file existing after a failed replace is not itself proof of the cause. Windows can reject a replace because of an open handle, an overlapping writer, or other filesystem state. This front will not change permissions, delete the temp file, suppress the exception, or retry the move.

The diagnostic surrounds the exact `VoxyConfig.save` method, if that public class name remains in the fixture, and records calls, completions, aggregate wall/current-thread CPU, render-vs-other thread count and `max_concurrent`. Interpretation:

- `max_concurrent > 1` is direct evidence that two Voxy saves overlap and makes an internal same-temp-name race actionable for a later Voxy-side fix;
- `max_concurrent == 1` rules out **overlapping instrumented `save()` calls only**. It does not identify an external file lock, stale temp file, antivirus/indexer activity, or Windows rename semantics;
- long wall with little current-thread CPU would be compatible with blocking/IO but is not proof of the filesystem mechanism;
- no filesystem mutation is added by the probe.

No production correction is justified until the exact custom binary either publishes source or the physical diagnostic demonstrates internal overlap. If overlap is proven, the safe candidate would have to serialize only Voxy's own save operation / unique temporary-write transaction while preserving the same final file content and error visibility, behind a kill-switch. That candidate is **not implemented here**.

## Diagnostic

Opt-in property, default off:

```text
-Dboot_optim.shaderVoxyVarianceDiagnostic=true
```

The branch adds observation only:

1. Flywheel `GlCompat.canCompileVersion` HEAD/RETURN: attempt, requested GLSL, success/failure, inclusive call wall and current-thread CPU.
2. Voxy `Capabilities.testShaderCompilesOk` HEAD/RETURN: attempt, source `#version`, success/failure, inclusive call wall and current-thread CPU.
3. Minecraft's existing `GlDebug.printDebugLog`: counts only `SHADER COMPILER` callbacks, groups messages separated by more than 5 s, records GLSL versions and stack-derived mod fingerprints. It does **not** install or replace a GL callback.
4. Voxy `VoxyConfig.save` HEAD/RETURN: aggregate wall/current-thread CPU and overlap count. No retry, suppression or file operation.
5. FancyMenu `ResourcePreLoader.preLoadAll` HEAD/RETURN: one broad inclusive wall/current-thread CPU interval. It does not instrument individual waits and does not modify PR #109.
6. At the first semantic `TitleScreen`, three aggregated markers are emitted before the existing benchmark stop path.

Markers:

```text
BOOTOPTIM_SHADER_VARIANCE ...
BOOTOPTIM_VOXY_SAVE_VARIANCE ...
BOOTOPTIM_FANCYMENU_PHASE_VARIANCE ...
```

`probe_wall_ms` and `probe_cpu_ms` are **inclusive call cost for the known tiny capability helpers**, not TTMM savings. FancyMenu phase wall is inclusive and its current-thread CPU excludes decoder/GC/other worker CPU. Voxy save wall is an inclusive method interval. Callback counts are events, not durations.

## Gate and single physical recipe

Hosted smoke first, using the pinned `exact-pack-2026-09-02-v1` fixture and this property. Valid only if the exact resource-selection gate passes, semantic title is reached and there are zero BootOptim Mixin failures. Expected llvmpipe behavior is allowed to differ: GL4.6 can make Flywheel's first probe succeed immediately and can eliminate all unsupported-version callbacks. That is a surrogate limitation, not diagnostic failure.

Only after hosted build/smoke passes: **one** fixed physical full-pack run, with the same ten validated external ZIPs/order and no MCEF/backend/OS/JVM toggles. Archive normal startup markers plus the three markers above. Do not reboot or purge caches for this run.

Decision after that one run:

- shader capability probes are causal only if their measured inclusive wall/CPU is material on the critical render thread; stable error count alone is irrelevant;
- Voxy becomes actionable only if overlap or material blocking time is demonstrated at `save()`; the exception alone is not a TTMM attribution;
- otherwise classify these messages as non-actionable secondary noise and continue variance attribution elsewhere.

## Related work deliberately not touched

- #90 MCEF
- #109 FancyMenu wait CPU
- #108 MoreCulling
- #110 VoxelShaper
- #95 / #102 rejected renderer deferral
- #113 broad preload process-CPU/GC/memory diagnostic

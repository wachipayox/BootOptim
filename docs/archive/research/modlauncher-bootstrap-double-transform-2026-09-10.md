# Agent 94 — Bootstrap double-transform causal attribution (2026-09-10)

Status: **ATTRIBUTION VALID.** The two observed `net.minecraft.server.Bootstrap` `ClassTransformer` passes are not two classloaders and not two class definitions. The first pass is a Mixin launch-plugin transformed-byte request during Mixin configuration selection/preparation; the second pass is the real SecureJarHandler class load/definition requested by Minecraft's startup worker. No optimization is implemented by this change.

Authority remains `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`. This continuation lives only on PR #228 branch `agent94/modlauncher-post-accept-audit-20260909`. It does not modify `main` or integration.

The requested `docs/research/boot-pipeline-program-2026-09-08.md` is absent at the exact integration SHA (404); its contents were not reconstructed.

## Exact versions and provenance

| component | exact version / pin |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 |
| FancyModLoader | 4.0.43 effective launch contract |
| ModDevGradle | 2.0.144 on BootOptim launch tooling |
| ModLauncher | 11.0.5, upstream commit `901c6ea849ae21ee7d464cd97113e77a6101a734` |
| ModLauncher `ClassTransformer.java` | Git blob `a0451dff688b78f075d0e79c3fba540361ba3304` |
| ModLauncher `TransformingClassLoader.java` | Git blob `89343a57fdc88a4c1cfea7b33e9962d081662257` |
| SecureJarHandler | **3.0.8 effective resolved launch artifact**, stock; not forked |
| Mixin | `net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7`, module `org.spongepowered.mixin` |
| ASM | 9.10.1 effective resolved launch artifacts |
| DevLaunch | 1.0.2 |
| hosted runtime | Oracle Java 25.0.4, `-XX:ActiveProcessorCount=4`, Ubuntu/Xvfb/llvmpipe surrogate |
| fork probe | `agent94-post-accept-v2`, ModLauncher same-GAV replacement already proven by #228 |

Upstream ModLauncher is LGPL-3.0. Mixin is MIT. This branch does not vendor an upstream source tree; `apply_probe.py` checks exact upstream commit and exact source blobs before applying the diagnostic patch.

## Diagnostic change

Commit `95ae2305af8985e647e2f3ba7c7926da9ff7202e` extends the already-proven ModLauncher fork probe at the narrowest request boundary: `TransformingClassLoader.maybeTransformClassBytes`.

For **only** `net.minecraft.server.Bootstrap`, and only when `-Dboot_optim.modlauncherForkTrace=true`, it records:

- monotonically increasing `request_id`;
- raw `context` and effective transformation `reason`;
- current thread;
- `TransformingClassLoader` class/name/identity hash;
- loader module and loader CodeSource;
- parent class/name/identity;
- target module from the loader's existing class-name-to-module mapping;
- TCCL class/name/identity;
- first non-ModLauncher/SJH/JDK caller, its module and CodeSource;
- a bounded 16-frame `StackWalker` fingerprint;
- correlation of every existing `ClassTransformer` stage with the same `request_id`.

It also classifies the already-existing SJH caller visible in the stack as either:

- `securejar_get_maybe_transformed_bytes`, or
- `securejar_reader_to_class`.

The hook does **not** perform a new class/resource lookup to discover a target CodeSource. Before `defineClass`, a `Class<?>` CodeSource does not yet exist; forcing another lookup would perturb the very lifecycle being measured. Instead the probe reports the existing loader/caller CodeSources and target module.

For probe-off or every non-target class, `maybeTransformClassBytes` still makes the single stock synchronous call to `classTransformer.transform`. For the target, the stock call remains in the same thread, position and order inside a `try/finally`; there is no executor, future, callback reordering, TCCL change, module change, reflection or SJH fork.

Commit `c892c3198895768ca23bfdad67b0503d011cf09a` only fixes the fork-build workflow's stale manifest assertion (`v1` -> the already-used `v2` marker). It does not change runtime behavior.

## CI / hosted evidence

### Fork build

Run `34415551696` is green: pinned checkout, patch application, upstream ModLauncher build/tests, manifest/module verification and artifact upload all succeeded.

Artifact:

- ID `10128926747`
- name `modlauncher-agent94-post-accept-probe-c892c3198895768ca23bfdad67b0503d011cf09a`
- Actions ZIP digest `sha256:c7a6241a4a5737c8f024e6fd4ce5aebc4c036c8f668e5a35a042be6740c8b079`

### Exact-pack profile smoke

Run `34415556178`, head `c892c3198895768ca23bfdad67b0503d011cf09a`, hosted exact-pack fixture `exact-pack-2026-09-02-v1` (`sha256:7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`).

The run reached the real main-menu marker at `uptime_ms=83424` (`result.json` reports 83.422 s). This is **not** an A/B result and is not a savings claim.

Unlike run `34413781996`, this run's `resource-selection-check.json` is valid:

- `valid: true`;
- expected resource list exactly equals observed;
- `reload_count: 1`;
- no resource-selection issues.

The workflow is nevertheless red because the older inline fork-profile parser still asserts `Expected exactly one strict Agent 94 transformer event, found 2`. That is a diagnostic-harness assumption contradicted by the now-proven two-request model, not a launch/resource failure. Paired control/candidate runs were skipped, as required; no A/B was performed.

Exact-pack diagnostic artifact:

- ID `10129058571`
- Actions ZIP digest `sha256:c8c697d6ca3ac4e3dc44637e72c47c412a2108e2eaf1719f7f2144b142565e6b`

## Causal attribution

### Request 1: transformed-byte side request from Mixin, not class definition

The first target record is:

```text
request_id=1
origin=securejar_get_maybe_transformed_bytes
raw_context=mixin
effective_reason=mixin
thread=main
loader_class=cpw.mods.modlauncher.TransformingClassLoader
loader_name=TRANSFORMER
loader_id=967609356
target_module=minecraft
tccl_class=cpw.mods.modlauncher.TransformingClassLoader
tccl_id=967609356
caller=org.spongepowered.asm.launch.MixinLaunchPluginLegacy#getClassNode
caller_module=org.spongepowered.mixin
caller_source=.../net.fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/.../sponge-mixin-0.15.2+mixin.0.8.7.jar...
```

Its bounded stack is decisive:

```text
TransformingClassLoader.maybeTransformClassBytes
  -> ModuleClassLoader.getMaybeTransformedClassBytes
  -> TransformingClassLoader.buildTransformedClassNodeFor
  -> LaunchPluginHandler.lambda$announceLaunch$10
  -> MixinLaunchPluginLegacy.getClassNode
  -> ClassInfo.forName
  -> MixinInfo.getTargetClass
  -> MixinInfo.readTargetClasses
  -> MixinInfo.parseTargets
  -> MixinConfig.prepareMixins
  -> MixinConfig.prepare
  -> MixinProcessor.prepareConfigs
  -> MixinProcessor.select
  -> MixinProcessor.checkSelect
  -> MixinProcessor.applyMixins
```

This is the ModLauncher launch-plugin byte-provider contract. `LaunchPluginHandler.announceLaunch` gives each launch plugin a callback backed by `TransformingClassLoader.buildTransformedClassNodeFor`; Mixin 0.8.7 stores that `ITransformerLoader` in `initializeLaunch`, and `MixinLaunchPluginLegacy.getClassNode(..., runTransformers=true)` invokes it when Mixin needs transformed class metadata.

SecureJarHandler's `getMaybeTransformedClassBytes` reads and transforms bytes but does **not** call `defineClass`. Therefore request 1 is a Mixin metadata/target-resolution side request during Mixin config selection/preparation, not a preload that publishes `Bootstrap` as a JVM class and not a second class definition.

The `reason=mixin` value also comes directly from ModLauncher's `announceLaunch` callback, which passes the launch-plugin key as the transformed-byte request context. It is not inferred from nearby log messages.

### Request 2: real class loading and definition

The second target record is:

```text
request_id=2
origin=securejar_reader_to_class
raw_context=null
effective_reason=classloading
thread=pool-8-thread-1
loader_class=cpw.mods.modlauncher.TransformingClassLoader
loader_name=TRANSFORMER
loader_id=967609356
target_module=minecraft
tccl_class=cpw.mods.modlauncher.TransformingClassLoader
tccl_id=967609356
caller=net.minecraft.client.main.Main#lambda$main$0
caller_module=minecraft
caller_source=.../build/moddev/artifacts/neoforge-21.1.248.jar...
```

Its stack is:

```text
TransformingClassLoader.maybeTransformClassBytes
  -> ModuleClassLoader.readerToClass
  -> ModuleClassLoader.lambda$findClass$20
  -> ModuleClassLoader.loadFromModule
  -> ModuleClassLoader.findClass
  -> ModuleClassLoader.loadClass
  -> java.lang.ClassLoader.loadClass
  -> net.minecraft.client.main.Main.lambda$main$0
  -> FutureTask.run
  -> ThreadPoolExecutor.runWorker
```

SecureJarHandler `readerToClass` calls `maybeTransformClassBytes(..., context=null)`, then performs package/signing/ProtectionDomain work and `defineClass`. ModLauncher maps the null context to `ITransformerActivity.CLASSLOADING_REASON`. Request 2 is therefore the actual JVM definition path for `Bootstrap`.

### Hypotheses resolved

| hypothesis | result | evidence |
| --- | --- | --- |
| two different TransformingClassLoaders | **rejected** | both requests `loader_id=967609356`, same `TRANSFORMER` loader and same TCCL identity |
| redefinition / two JVM definitions | **rejected** | request 1 uses `getMaybeTransformedClassBytes`, which returns bytes only; request 2 uses `readerToClass`, which proceeds to `defineClass` |
| ordinary resource read accidentally enters transform pipeline | **rejected** | request 1 has explicit ModLauncher launch-plugin context `mixin` and caller `MixinLaunchPluginLegacy.getClassNode` |
| frame-computation side-load | **rejected for request 1** | effective reason is `mixin`, not `computing_frames` |
| Mixin launch-plugin metadata request followed later by actual class load | **confirmed** | exact caller stack + reason + SJH origin classification + identical loader identity |
| 5-7 s gap is itself duplicate Bootstrap transformation cost | **rejected** | both target transforms are millisecond-scale; the wall lies between the completed Mixin side request and later real class load |

## Current-run subsegments

All numbers below are **single-run monotonic phase wall or target callback wall** from hosted run `34415556178`. They are attribution measurements, not A/B savings.

| subsegment | owner / semantic role | wall |
| --- | --- | ---: |
| request 1 `ClassTransformer` begin -> return | Mixin-requested transformed-byte side pass through ModLauncher | **1.607221 ms** |
| #207-compatible strict `transform_accept` -> request 1 transform return | accepted diagnostic transformer tail in side request | **0.633574 ms** |
| request 1 writer create + accept + to-bytes callback sum | ASM/ModLauncher in side request | **0.216076 ms** |
| request 1 transform return -> request 2 transform begin | intervening launch/Mixin-preparation/startup lifecycle; not one callback | **5.797496075 s** |
| request 2 `ClassTransformer` begin -> return | actual class-loading transform | **10.896984 ms** |
| request 2 launch plugins AFTER | launch-plugin processing during actual load | **8.872314 ms** |
| request 2 writer create + accept + to-bytes callback sum | ASM/ModLauncher | **0.993697 ms** |
| request 2 transform return -> actual `Bootstrap.bootStrap` entry | SJH post-transform definition + JVM handoff | **6.600041 ms** |
| strict side-request accept -> actual Bootstrap entry | cross-request/cross-thread phase | **5.815626674 s** |

The inter-request span is **99.688%** of this run's strict-accept -> Bootstrap-entry wall. The earlier run `34413781996` independently showed the same topology with a 7.170534417 s inter-invocation gap and 7.182459982 s accept->entry wall. Absolute magnitude varies across hosted runs; the causal topology is the durable result.

Nearby console output is Mixin-heavy and includes a Datafixer report, but those messages are not used to assign the whole 5.797 s span. The only Mixin ownership claimed here is the exact request-1 synchronous stack shown above.

## Lifecycle interpretation and optimization boundary

The first `Bootstrap` side request occurs while Mixin is selecting/preparing configurations and resolving a target class into `ClassInfo`. This is **real required Mixin preparation semantics**: the Mixin service asks ModLauncher for transformed bytes so its metadata view includes earlier transformation-service effects while avoiding a JVM class definition.

The later `Minecraft Main` worker asks the same TransformingClassLoader to actually load `Bootstrap`; SecureJarHandler rereads/retransforms the bytes as part of the stock class-definition path.

There is technically duplicated per-class transform work for `Bootstrap`, but the measured duplicate request itself is only about **1.6 ms** in this hosted run (about 2.2 ms in the previous run). Eliminating only that repeated transform cannot explain or recover the multi-second inter-request wall.

A global transformed-byte/side-load cache is specifically not reopened: PR #43 already measured only 3.71% side-load cache hits and about 41.7 ms estimated benefit with large retained state. More importantly, handing request-1 output directly to request 2 is not generically equivalent: transformation-service state, launch-plugin state/audit context and request reason differ between `mixin` metadata inspection and `classloading`, and the second pass is the publication path that ultimately feeds `defineClass`.

The high-value remaining question is narrower: **what serialized work inside Mixin's `checkSelect/select/prepareConfigs` path after resolving `Bootstrap` delays the point at which Minecraft's actual Bootstrap load is submitted/allowed?** Existing `mixin-pipeline.md` establishes seconds-scale Mixin prepare/apply work globally, but this run does not prove the entire 5.797 s inter-request span is recoverable Mixin work and does not prove that moving it would shorten menu TTMM.

No optimization is implemented in this PR continuation.

## Contract changes and semantic risks

The diagnostic contract adds `BOOTOPTIM_ML_FORK_REQUEST` / `_END` lines and `request_id` correlation on the existing target-only ModLauncher fork trace. It does not change public ModLauncher API, module descriptor, services, GAV, callback order, executor use, thread ownership, GAME/PLUGIN layers, fallback loaders or TCCL.

Risks are bounded but non-zero:

- `StackWalker` and `System.err` add diagnostic overhead to the two target requests; therefore total startup time from this run is not a performance baseline.
- Loader identity uses `System.identityHashCode`; it is a within-process correlation token, not a stable ID across launches.
- Caller CodeSource is observed from already-loaded caller classes; target CodeSource is deliberately not forced before definition.
- The patch is exact-commit/exact-blob pinned and must fail closed on ModLauncher source drift.
- Launch plugins may change request contexts in future versions; `mixin` here is proven only for the exact pinned stack.
- Effective SJH is 3.0.8 even though the audited ModLauncher source build declares 3.0.4; production/future experiments must preserve the actually resolved NeoForge graph unless SJH is separately versioned and tested.

There is no OpenGL/render-thread offload, no visual callback change and no mod lifecycle reorder. The current run reached main menu and passed the resource-selection contract, but a diagnostic StackWalker run is not a gameplay-compatibility proof.

## Savings ceiling and next decision

Plausible direct savings from eliminating the **duplicated Bootstrap transform itself** are only millisecond-scale: the first side request's complete `ClassTransformer` wall was **1.607 ms** in run `34415556178` and 2.189 ms in the previous profile. That is the only directly bounded duplicate established here.

The mathematical inter-request wall was **5.797 s** in this run (7.171 s previously), but it is **not a savings estimate**. It contains Mixin selection/preparation and other launch work, some potentially concurrent, and no equivalent bypass or earlier-publication design has been proven.

Next decision: keep ModLauncher/SJH scheduling stock and instrument the synchronous Mixin selection/preparation boundary that encloses request 1, preferably at coarse `MixinProcessor.checkSelect/select/prepareConfigs` or equivalent exact-version points, with a boundary to when `net.minecraft.client.main.Main` submits/executes the Bootstrap worker. The purpose is to partition the 5-7 s inter-request wall causally before considering any architectural optimization. Do not implement a byte cache, classloader parallelization, FML scheduler rewrite or early `defineClass` handoff from this result.

## Links

- PR #228: https://github.com/wachipayox/BootOptim/pull/228
- request-origin instrumentation commit: https://github.com/wachipayox/BootOptim/commit/95ae2305af8985e647e2f3ba7c7926da9ff7202e
- manifest-gate fix: https://github.com/wachipayox/BootOptim/commit/c892c3198895768ca23bfdad67b0503d011cf09a
- green fork-probe run: https://github.com/wachipayox/BootOptim/actions/runs/34415551696
- fork artifact: https://github.com/wachipayox/BootOptim/actions/runs/34415551696/artifacts/10128926747
- hosted causal profile run: https://github.com/wachipayox/BootOptim/actions/runs/34415556178
- exact-pack diagnostic artifact: https://github.com/wachipayox/BootOptim/actions/runs/34415556178/artifacts/10129058571
- ModLauncher upstream pin: https://github.com/McModLauncher/modlauncher/commit/901c6ea849ae21ee7d464cd97113e77a6101a734

# Agent 94 — ModLauncher post-accept fork audit (2026-09-09)

Status: **NO-GO for the proposed `gameContents -> resolveAndBind worker -> stock publication` architecture as an optimization of `transform_accept -> Bootstrap entry`; GO and now physically proven for a version-pinned ModLauncher replacement/probe. The profile smoke reached the real main menu with the fork loaded, but the overall exact-pack benchmark is INVALID because the later resource-selection contract failed.**

Authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`. This branch is diagnostic/tooling only and does not alter integration, production launch behavior, FML scheduling, callbacks, classloader ownership, render/GL work or gameplay.

The requested `docs/research/boot-pipeline-program-2026-09-08.md` is not present at the refreshed integration SHA; PR #200 independently recorded the same absence. Its contents are not reconstructed or invented here.

## Exact version pins

| component | exact pin / evidence |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 (`gradle.properties`) |
| FML | 4.0.43 on the audited 1.21.1 launch path (also recorded by the #207 diagnostic source) |
| ModLauncher | `11.0.5+main.901c6ea8`, upstream commit `901c6ea849ae21ee7d464cd97113e77a6101a734` |
| SecureJarHandler declared by that ModLauncher source checkout | `3.0.4` in upstream `gradle.properties` |
| SecureJarHandler selected by the actual NeoForge 21.1.248 hosted launch graph | **`3.0.8`**, proven by Gradle artifact resolution in run `34413781996`; the fork does not replace SJH |
| Java for project compile | 21; hosted exact-pack runtime remains the separately pinned Oracle 25.0.4 surrogate |

Do not conflate the source checkout's declared SJH 3.0.4 with the effective NeoForge launch graph's 3.0.8. The reason for Gradle's selected version was not separately attributed here and is not needed for this target.

Upstream ModLauncher is LGPL-3.0. No upstream source is vendored by this branch. `tools/modlauncher-fork-probe/apply_probe.py` checks the exact upstream commit and exact Git blob of `ClassTransformer.java`, creates a diagnostic helper in the upstream checkout, and adds explicit manifest provenance. The workflow builds from the public upstream repository.

## Established measurement boundary

PR #207 (`46060e80087edac17801a4c3a5a313559533a61a`) measured, without changing scheduling:

- hosted exact-pack: SERVICE `transformers()` -> Bootstrap entry **8.317 s inclusive phase wall**;
- hosted exact-pack: strict `MinecraftBootstrap.bootStrap()V` transform acceptance -> injected Bootstrap entry **5.130 s inclusive phase wall**;
- four valid laptop traces: parent phase **61.906-64.543 s**, accept -> entry **44.361-51.350 s**.

These are phase walls, not CPU sums and not automatically recoverable savings.

PR #211's same-run JFR attribution later measured a comparable accept -> entry phase at **6.036645 s** with 375 execution samples, 350 on `main`; Mixin was the largest single full-stack owner (121/375 samples) but ModLauncher, ASM and other transformation work remained. Sample counts are ownership evidence, not wall-clock savings.

Historical PR #48 measured the external ASM writer tail over 1,199 Mixin-rewrite classes at **760.734 ms total** (`ClassNode.accept` 734.650 ms + `toByteArray` 26.084 ms) in its Windows warm exact-pack run. That rejects an unobserved multi-second writer-only explanation; it is not a measurement of the single Bootstrap target in #207.

## Source-level launch map

Exact upstream `Launcher.run` order at `901c6ea8`:

1. `triggerScanCompletion(moduleLayerHandler)` returns final transformation-service resources.
2. `gameContents = ...toList()` materializes the GAME `SecureJar` references. The list is already a structural snapshot; its `SecureJar` objects remain live/lazy shared objects.
3. `gameContents.forEach(addToLayer(GAME, jar))` registers those exact objects.
4. `initialiseServiceTransformers()` calls each transformation service's transformer-gather callback in service iteration order.
5. `launchPlugins.offerScanResultsToPlugins(gameContents)` calls each `ILaunchPluginService.addResources` with the shared GAME `SecureJar` list. This is callback/state publication, not a pure descriptor pass.
6. `validateLaunchTarget` checks the selected launch service.
7. `buildTransformingClassLoader(...)` calls `ModuleLayerHandler.buildLayer(GAME, ...)`:
   - build `SecureJar[]` / target module names;
   - `JarModuleFinder.of(...)`;
   - `Configuration.resolveAndBind(...)`;
   - collect parent layers;
   - construct `TransformingClassLoader` / SecureJarHandler `ModuleClassLoader`;
   - `ModuleLayer.defineModules(...)`;
   - publish GAME `LayerInfo` into `completedLayers`;
   - publish BOOT fallback loader;
   - publish GAME loader as PLUGIN fallback.
8. `Thread.currentThread().setContextClassLoader(classLoader)` publishes TCCL.
9. `launchService.launch(...)`:
   - `launchPluginHandler.announceLaunch(...)` invokes `initializeLaunch` callbacks;
   - the FML launch handler is invoked in the GAME layer.
10. GAME `ModuleClassLoader` resolves Minecraft classes. SecureJarHandler `readerToClass` reads bytes and calls `maybeTransformClassBytes`; ModLauncher's `TransformingClassLoader` delegates that to `ClassTransformer.transform`.
11. `ClassTransformer.transform` performs, in order:
   - `handlesClass` / launch-plugin set selection;
   - `ClassReader.EXPAND_FRAMES`;
   - launch plugins `BEFORE`;
   - PRE_CLASS / field / method / CLASS transformer voting and application;
   - **#207 strict BootOptim Bootstrap transformer acceptance occurs inside this transformer-application sequence**;
   - any remaining transformers continue in stock order;
   - launch plugins `AFTER`;
   - `TransformerClassWriter.createClassWriter`;
   - `ClassNode.accept`;
   - final `ClassWriter.toByteArray`.
12. Control returns to SecureJarHandler `ModuleClassLoader.readerToClass`, which performs package/protection-domain/signing work and `defineClass`, then package-module publication.
13. JVM verification/linking/class initialization and ordinary dependency loading continue until the call actually enters `Bootstrap.bootStrap()`; #207's injected entry marker is there.

### Consequence for the proposed architecture

`Configuration.resolveAndBind`, GAME `ModuleClassLoader` construction, `ModuleLayer.defineModules`, fallback publication and TCCL publication all happen **before step 9 launch dispatch**. The strict Bootstrap transform acceptance happens later, inside step 11. Therefore moving or overlapping step 7 cannot reduce the already-isolated `transform_accept -> Bootstrap entry` phase measured by #207. At most it can affect the earlier `transformers() -> transform_accept` child of the parent phase.

That timing fact is the primary NO-GO, independent of whether module resolution can eventually be made thread-safe.

## Concurrency / publication classification

| work | owner | purity / concurrency classification | targeted accept->entry leverage |
| --- | --- | --- | ---: |
| `gameContents ... toList()` | ModLauncher | structural snapshot already exists; objects are live `SecureJar`s | none after accept |
| GAME `addToLayer` | ModLauncher | mutable launch-layer registration; must precede build | none after accept |
| `gatherTransformers` | transformation services | arbitrary callback/stateful | none after accept |
| `ILaunchPluginService.addResources` | launch plugins | arbitrary callback over shared `SecureJar`s; no generic thread-safety contract | none after accept |
| `JarModuleFinder.of` / descriptor access | ModLauncher + SJH | descriptor-oriented but can force lazy `SecureJar` metadata; no cross-thread contract proven here | pre-accept only |
| `Configuration.resolveAndBind` | JDK + finder | logically resolution work, but inputs depend on prior callbacks and live module refs | pre-accept only |
| `ModuleClassLoader` constructor | SJH | **not pure**: builds lookup state and binds parent layers to the loader | pre-accept only |
| `ModuleLayer.defineModules` | JDK | classloader/module publication boundary; serial | pre-accept only |
| GAME/PLUGIN fallback + TCCL | ModLauncher/SJH/JDK | observable loader publication; serial and ordered | pre-accept only |
| transformation-service transforms | ModLauncher/services | stateful voting + callbacks on the same mutable `ClassNode` | target transformation only; serial |
| launch plugins `AFTER` | launch plugins | stateful callback on same mutable `ClassNode` | target transformation only; serial |
| ASM writer | ModLauncher/ASM | consumes final node; frame computation may side-load hierarchy | target transformation only |
| `defineClass` / package / protection domain | SJH/JVM | classloader state/publication | after a transforming-load return; serial |
| verification/link/init/dependency loads | JVM + GAME loader | order-sensitive class initialization and further transformations | after transform; serial |

## Architecture decision

Rejected for this target:

```text
gameContents
  -> immutable descriptor snapshot
  -> worker Configuration.resolveAndBind
  -> barrier
  -> stock ModuleClassLoader / ModuleLayer / TCCL publication
  -> launch
```

Reasons:

1. The list-level snapshot already exists at `gameContents.toList()`; making another list snapshot does not isolate `SecureJar` internals.
2. `addResources`, transformer gathering and module inputs are callback/stateful and must finish before a valid snapshot can be claimed.
3. `ModuleClassLoader` construction itself is not a pure prepare stage because it binds parent layers to the new loader.
4. `ModuleLayer.defineModules`, fallback publication and TCCL are explicitly observable publication points and must stay ordered.
5. Most importantly, all of this precedes strict Bootstrap transform acceptance, so it cannot shorten the isolated accept -> entry phase even if implemented perfectly.

This does **not** rule out a future, separately measured optimization of the earlier `transformers() -> transform_accept` child. It does rule out presenting module-resolution workerization as the solution to #207's hosted/laptop post-accept phase.

## Diagnostic fork and launcher-layer replacement: now proven

`tools/modlauncher-fork-probe/apply_probe.py` builds exact upstream ModLauncher commit `901c6ea849ae21ee7d464cd97113e77a6101a734` and exact `ClassTransformer.java` Git blob `a0451dff688b78f075d0e79c3fba540361ba3304`.

The generated fork adds only target-gated diagnostics for `net.minecraft.server.Bootstrap` when `-Dboot_optim.modlauncherForkTrace=true`: plugin selection, plugins BEFORE/AFTER, each applied transformation-service transformer with owner + labels, writer construction/accept/to-bytes, and transform return. It is disabled by default and does not wrap executors or reorder callbacks.

Manifest identity:

```text
BootOptim-Fork-Probe: agent94-post-accept-v2
BootOptim-Upstream-Commit: 901c6ea849ae21ee7d464cd97113e77a6101a734
```

The diagnostic runtime path uses a Gradle `exclusiveContent` Maven repository only for `cpw.mods:modlauncher`, publishes the fork under the **same exact GAV** `cpw.mods:modlauncher:11.0.5`, and therefore replaces rather than appends a second launcher artifact.

Hosted run `34413781996` proved before launching Minecraft:

- one physical ModLauncher core JAR across the resolved launch configurations;
- its SHA-256 equals the just-built fork artifact;
- named module identity begins `cpw.mods.modlauncher@11.0.5`;
- fork manifest marker and exact upstream commit marker are present;
- one physical SecureJarHandler core JAR;
- effective SJH provenance is `cpw.mods:securejarhandler:3.0.8`;
- ModLauncher provenance in `runtimeClasspath` / legacy launch inputs points to the local exclusive fork repository rather than a second stock JAR.

The live JVM then emitted exactly one fork identity record:

```text
BOOTOPTIM_ML_FORK_IDENTITY probe=agent94-post-accept-v2 module=cpw.mods.modlauncher named=true source=union:.../.agent94-ml-fork-repo/cpw/mods/modlauncher/11.0.5/modlauncher-11.0.5.jar... loader=cpw.mods.cl.ModuleClassLoader@335eadca
```

and later reached the real startup endpoint:

```text
BOOTOPTIM_STARTUP phase=main_menu uptime_ms=60387 processors=4 heap_used_mib=2150 heap_max_mib=6144
```

This closes the earlier packaging question: **a reproducible ModDevGradle/hosted launcher-layer replacement of ModLauncher is implementable without duplicate ModLauncher modules or a reflective BootOptim substitute.** It is still diagnostic-only and exact-version-pinned.

## New target trace: two Bootstrap transformations, not one long writer tail

The same run emitted two complete target `ClassTransformer` invocations around the one-shot #207-compatible `transform_accept` boundary:

| subsegment | thread | wall |
| --- | --- | ---: |
| first target `ClassTransformer` begin -> return | `main` | **2.188558 ms** |
| strict `transform_accept` -> first target transform return | `main` | **0.777820 ms** |
| first transform writer callbacks (`create + accept + toByteArray`) | `main` | **0.264025 ms callback sum** |
| first target transform return -> second target transform begin | cross-thread | **7.170534417 s** |
| second target `ClassTransformer` begin -> return | `pool-8-thread-1` | **6.863082 ms** |
| second plugins `AFTER` callback | `pool-8-thread-1` | **5.444649 ms** |
| second writer callbacks (`create + accept + toByteArray`) | `pool-8-thread-1` | **0.576692 ms callback sum** |
| second target transform return -> injected Bootstrap entry | `pool-8-thread-1` | **4.284663 ms** |
| strict `transform_accept` -> Bootstrap entry | cross-thread | **7.182459982 s** |

The 7.170534417 s inter-invocation gap is **99.834%** of this run's 7.182459982 s accept -> entry wall. Therefore this run directly rejects the working idea that the post-accept residual is primarily “remaining transformer callbacks + ASM writer + immediate `defineClass`”. The first accepted transformation is already back out of `ClassTransformer` in under 0.8 ms; a second target transformation occurs ~7.17 s later and is itself only ~6.9 ms.

This also means the original strict acceptance marker is earlier than the transforming-load invocation nearest actual Bootstrap entry. Treating the whole accept -> entry wall as one continuous `ClassTransformer` suffix is incorrect for this pack.

`tools/modlauncher-fork-probe/parse_profile.py` was added after this run to preserve repeated target invocations instead of flattening them into one stage map. It groups begin/return by thread, finds the invocation containing the one-shot accept boundary, identifies the last completed target transform before entry, and reports the inter-invocation residual without summing overlapping wall.

### What is visible inside the 7.17 s gap

Console chronology after the first target transform return and before the second target transform begin contains substantial Mixin configuration/target-resolution activity, missing-target probes, AsyncParticles class-adjuster setup, MixinExtras initialization and a JNA native-access warning. It also contains `Datafixer Bootstrap` reporting **331 ms** for 229 optimizations. These observations are evidence of work occurring in the interval, **not causal attribution of the full 7.17 s**; concurrent/inclusive work must not be summed into the gap.

The exact production caller/classloader identity of each target transformation is therefore the next causal question. SecureJarHandler's `ModuleClassLoader.readerToClass` calls `maybeTransformClassBytes` immediately before package/signing/`defineClass`, and upstream ModLauncher's `TransformingClassLoader` delegates that hook to `ClassTransformer.transform`. A next target-only probe should record transforming-classloader identity/caller fingerprint per invocation; no global classloader wrapping is justified.

## Exact-pack validity of run 34413781996

The run is **valid for launcher replacement identity, real-main-menu reachability, and monotonic target trace ordering**, because those checks/markers occurred before the later resource gate.

It is **NOT a valid exact-pack performance benchmark and NOT behavior-equivalence proof**. After the menu marker, `check_resource_selection.py` failed:

- selected packs / priority order differed from the fixture reference;
- reload 2 external packs/order differed;
- resource-pack fallback was reported;
- the run's `options.txt` ended with an empty resource-pack selection.

The workflow therefore correctly remained red. No TTMM comparison, savings claim, A/B result or production promotion may use this run. The fork is not cleared for integration by this smoke.

Run: `https://github.com/wachipayox/BootOptim/actions/runs/34413781996`

Diagnostic artifact: `https://github.com/wachipayox/BootOptim/actions/runs/34413781996/artifacts/10128377055`

Artifact ZIP digest reported by Actions: `sha256:e5fba8629450aee6a3e108d79a690a78326b40eb5c121b115639c05f6c1cbe05`.

## Next highest-value decision

1. Keep the module-resolution worker architecture closed for the post-accept target; it is on the wrong side of the measured boundary.
2. Use the proven exact-GAV launcher replacement only as a diagnostic vehicle. Add target-only transforming-classloader identity/caller context for both `Bootstrap` invocations; do not wrap global classloading.
3. Attribute the **first transform return -> second transform begin** residual causally. Mixin-heavy log chronology makes Mixin/lifecycle preparation the leading evidence-backed family, but a stack/classloader marker or bounded JFR is required before assigning the wall.
4. Separately reproduce the resource-selection failure with a stock/control launch before any A/B performance claim. If stock is clean and fork is not, the diagnostic fork has observable pack behavior and must not be promoted. If both fail identically, repair the hosted fixture/harness before performance testing.
5. Only after a resource-valid paired run should an optimization candidate be evaluated. No new parallel module/SJH/service design is justified by the current post-accept evidence.

## Savings ceiling

- Absolute mathematical ceiling of the historical isolated hosted post-accept phase: **5.130 s** in #207; not a recoverable-savings claim.
- Comparable #211 JFR phase: **6.036645 s**; distributed ownership.
- This diagnostic smoke's accept -> entry wall: **7.182459982 s**, but the run is resource-invalid and cannot serve as A/B baseline/candidate performance evidence.
- Within this smoke, **7.170534417 s (99.834%)** lies between the first accepted target transform return and the next target transform begin; direct writer work is sub-millisecond on the accepted invocation.
- Physical laptop post-accept phase remains **44.361-51.350 s**, hardware-specific and not interchangeable with hosted timing.

No TTMM improvement is claimed by this audit, the fork, or run `34413781996`.

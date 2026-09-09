# Agent 94 — ModLauncher post-accept fork audit (2026-09-09)

Status: **NO-GO for the proposed `gameContents -> resolveAndBind worker -> stock publication` architecture as an optimization of `transform_accept -> Bootstrap entry`; GO for a version-pinned target-only fork probe before choosing a different intervention.**

Authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`. This branch is diagnostic/tooling only and does not alter integration, production launch behavior, FML scheduling, callbacks, classloader ownership, render/GL work or gameplay.

The requested `docs/research/boot-pipeline-program-2026-09-08.md` is not present at the refreshed integration SHA; PR #200 independently recorded the same absence. Its contents are not reconstructed or invented here.

## Exact version pins

| component | exact pin / evidence |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 (`gradle.properties`) |
| FML | 4.0.43 on the audited 1.21.1 launch path (also recorded by the #207 diagnostic source) |
| ModLauncher | `11.0.5+main.901c6ea8`, upstream commit `901c6ea849ae21ee7d464cd97113e77a6101a734` |
| SecureJarHandler | ModLauncher pin `3.0.4`; upstream 3.0.4 changelog terminates at PR #71, merge commit `162d82167f4351bf3f9a4986c928d3cacf6ff137` |
| Java for project compile | 21; hosted exact-pack runtime remains the separately pinned Oracle 25.0.4 surrogate |

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
10. GAME `ModuleClassLoader` resolves Minecraft classes. For `net.minecraft.server.Bootstrap`, SecureJarHandler `readerToClass` reads bytes and calls `maybeTransformClassBytes`, which enters ModLauncher `ClassTransformer.transform`.
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
| `ModuleClassLoader` constructor | SJH | **not pure**: builds lookup state and invokes private `ModuleLayer.bindToLoader` on parents | pre-accept only |
| `ModuleLayer.defineModules` | JDK | classloader/module publication boundary; serial | pre-accept only |
| GAME/PLUGIN fallback + TCCL | ModLauncher/SJH/JDK | observable loader publication; serial and ordered | pre-accept only |
| remaining transformation-service transforms | ModLauncher/services | stateful voting + callbacks on the same mutable `ClassNode` | inside target phase; serial |
| launch plugins `AFTER` | launch plugins | stateful callback on same mutable `ClassNode` | inside target phase; serial |
| ASM writer | ModLauncher/ASM | consumes final node; frame computation may side-load hierarchy; worker offload would immediately rejoin on serial class definition and historical total is sub-second across all rewrite classes | small/unknown single-target share |
| `defineClass` / package / protection domain | SJH/JVM | classloader state/publication | inside target phase; serial |
| verification/link/init/dependency loads | JVM + GAME loader | order-sensitive class initialization and further transformations | inside target phase; serial |

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

This does **not** rule out a future, separately measured optimization of the earlier `transformers() -> transform_accept` child. It does rule out presenting module-resolution workerization as the solution to #207's 5.130 s post-accept hosted phase or the 44-51 s laptop post-accept phase.

## Diagnostic fork probe on this branch

`tools/modlauncher-fork-probe/apply_probe.py` builds against exact upstream ModLauncher commit `901c6ea849ae21ee7d464cd97113e77a6101a734` and exact `ClassTransformer.java` Git blob `a0451dff688b78f075d0e79c3fba540361ba3304`.

The generated fork adds only target-gated diagnostics for `net.minecraft.server.Bootstrap` when `-Dboot_optim.modlauncherForkTrace=true`:

- launch-plugin selection;
- launch plugins BEFORE;
- each actually applied transformation-service transformer, reporting owner + labels in stock sequence;
- launch plugins AFTER;
- writer construction;
- `ClassNode.accept`;
- final `toByteArray`.

Manifest identity:

```text
BootOptim-Fork-Probe: agent94-post-accept-v1
BootOptim-Upstream-Commit: 901c6ea849ae21ee7d464cd97113e77a6101a734
```

The helper is disabled by default and makes no scheduling decision. It neither wraps executors nor changes transformer/plugin ordering. It is a **diagnostic artifact only**.

### Injection contract — deliberately not implemented yet

The fork must never coexist with stock ModLauncher on the module path/class path. A valid runtime experiment must replace the exact stock ModLauncher artifact at the launcher/bootstrap layer and prove before timing that:

- exactly one `cpw.mods.modlauncher` module is present;
- the manifest contains the Agent 94 fork marker;
- `BOOTOPTIM_ML_FORK` markers are emitted for the strict Bootstrap target;
- effective ModLauncher API/service versions remain the expected 11.0.5 contract;
- no duplicate `META-INF/services` provider set is introduced;
- stock SecureJarHandler remains singular unless a separate SJH fork is explicitly tested.

Until those checks exist, **no exact-pack A/B is authorized from this branch**. A compile artifact is not proof that the pack used the fork.

## Next highest-value decision

1. Build/verify the pinned ModLauncher diagnostic fork (this branch's workflow).
2. Design a launcher-layer replacement that removes stock ModLauncher rather than appending a second JAR; gate it on the manifest/module/service checks above.
3. Run one hosted exact-pack **profile smoke**, not A/B, together with the #207 strict accept/entry instrumentation. Attribute the single Bootstrap target suffix into:
   - remaining service transformers after the BootOptim accept edge;
   - launch-plugin AFTER;
   - ASM writer;
   - residual after `ClassTransformer` returns.
4. Only if the residual after `ClassTransformer` remains material, add a second exact-version SecureJarHandler probe around `readerToClass` package/signing/`defineClass`; do not fork SJH pre-emptively.
5. Choose an optimization only from a materially dominant, semantically bounded owner. If Mixin/other transformation callbacks still dominate, the next design belongs to that owner rather than module-resolution workerization.

## Savings ceiling

- Absolute mathematical ceiling of the isolated hosted post-accept phase: **5.130 s** in #207; this is not a plausible recoverable-savings claim.
- Comparable JFR phase: **6.036645 s**; distributed ownership means no single safe bypass is currently established.
- Physical laptop post-accept phase: **44.361-51.350 s**; hardware-specific magnitude, not interchangeable with hosted timing.
- Known external writer work is historically only **0.760734 s across 1,199 rewritten classes**, so a writer-only fork does not have evidence for a seconds-scale hosted win.

No TTMM improvement is claimed by this audit or by building the diagnostic artifact.

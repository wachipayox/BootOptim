# FML post-discovery causal trace — 2026-09-08

Status: **DIAGNOSTIC / STACKED / VALIDATION PENDING**

This work is diagnostic infrastructure only. It does not claim a TTMM improvement and must not be merged as a production optimization merely because CI is green.

## Base and stacking

Integration authority at assignment start: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This branch is intentionally stacked on Agent 66 PR #201 head `a957b77c58c52edf1bccf68c5bc394cd84432e51`, which itself is stacked on trace-core PR #200. Neither #200 nor #201 is integrated in the authority SHA above. After #200/#201 are resolved, this branch must be rebased so its diff contains only the post-Discovery causal instrumentation and this research record.

PR #198's dual-writer architecture is not reused. `StructuredBootTrace` schema v1 remains the single writer/schema source from #200, and #201's rule that SERVICE/main must physically contain the shared trace source remains intact.

## Exact 1.21.1 sequence mapped before instrumentation

The exact pack uses NeoForge `21.1.248`, whose dependency set contains FancyModLoader `4.0.43` and ModLauncher `11.0.5`. Public 1.21.1 crash/runtime evidence identifies `MC-BOOTSTRAP/fml_loader@4.0.43` and NeoForge `21.1.248` together; upstream FML source documents the same loading flow used here.

The relevant sequence is:

1. ModLauncher SERVICE initialization runs BootOptim's early transformation service.
2. NeoForge root candidate locators run; #201 brackets these as `root_mod_discovery`.
3. dependency locators/JarJar resolution run; #201 brackets these as `dependency_discovery`.
4. ModLauncher launches the `forgeclient` target. Minecraft/NeoForge client bootstrap later calls `ModLoader.gatherAndInitializeMods(syncExecutor, parallelExecutor, periodicTask)`.
5. `gatherAndInitializeMods` first consumes the already-resolved `LoadingModList`, then calls `FMLLoader.getCurrent().backgroundScanHandler.waitForScanToComplete(periodicTask)`. This is an actual global wait/barrier before mod containers are finalized.
6. FML builds `ModContainer`s and enters `constructMods`.
7. `dispatchParallelTask("Mod Construction", ...)` creates one future per mod in sorted order. Each future is gated by `CompletableFuture.allOf` of that mod's declared dependency futures before `modContainer.constructMod()` is called. This is the first concrete per-mod causal DAG before the regular mod constructors/entrypoints.
8. The BootOptim regular mod entrypoint occurs inside its `ModContainer.constructMod()` task; therefore tracing the real call site gives a structured endpoint that can be correlated with the existing `mod_entrypoint_ms` marker without adding a second writer in the regular mod layer.

Later lifecycle events, resource reload, ModelBakery, FancyMenu, MCEF and rendering are intentionally out of scope.

## Selected boundaries and why

Only three post-Discovery boundaries are added:

- `fml_gather_and_initialize_mods` `task_begin/task_end`, dependent on #201's `dependency_discovery` task. It is an inclusive structural/cause task, **not** a summable savings estimate.
- `fml_background_scan_complete` `barrier_wait/barrier_open` exactly around FML's existing `waitForScanToComplete` call. No work is moved and no synthetic wait is introduced.
- `fml_mod_construction` tasks exactly around the real `ModContainer.constructMod()` invocation. In detailed modes each task records `mod_id`, structural parent `fml_gather_and_initialize_mods`, and dependency task IDs derived from FML's already-resolved `LoadingModList.getDependencies(modInfo)`. Root construction tasks fall back to the Discovery predecessor. The injection matches the call site, not synthetic lambda numbering, so it is resilient to javac lambda renumbering within the pinned FML line.

The transformer targets only `net.neoforged.fml.ModLoader`. It is registered only when structured trace mode is not `off`; default startup installs no diagnostic transformer. Benchmark mode avoids dependency-array construction and relies on trace-core's existing no-clock/no-buffer/no-JSON event path.

## Classloader strategy

A regular-mod Mixin was deliberately rejected for this stage because #201 demonstrated that SERVICE/main class identity is a real boundary. Referencing a separately loaded trace copy from TRANSFORMER could recreate duplicate global/writer risk. Instead the existing early `ITransformationService` owns the transformer and the injected calls target a public helper compiled into the same bootstrap SERVICE output as #201's shared trace source.

This classloader assumption is not considered proven until the exact-pack profile smoke reaches `main_menu` with exactly one header/summary and no BootOptim class/module failures. Failure must remain fail-open at hook execution; no `NoClassDefFoundError` catch or reflective silent fallback is added.

## Validation contract

Required profile smoke properties:

```text
-Dboot_optim.bootTrace.mode=profile
-Dboot_optim.bootTrace.origin=hosted_exact_pack
-Dboot_optim.bootTrace.endpoint=main_menu
```

Acceptance requires package/startup green, exact-pack `main_menu`, zero BootOptim Mixin/classloader failures, one JSONL header and summary, zero drops/flush failures, balanced Discovery/gather/construction task pairs and background-scan barrier pair, and at least one `fml_mod_construction` event for `boot_optim`. The analyzer must continue to distinguish reported CPU sum, inclusive task wall, and dependency-chain critical-path union. Inclusive gather/Discovery values are never added and called savings.

## Measurement status

Baseline evidence inherited from #201 hosted exact-pack profile smoke `34273366662`:

- origin `hosted_exact_pack`, endpoint `main_menu`;
- `main_menu_ms=88782`, `mod_entrypoint_ms=30020`;
- `root_mod_discovery=557.368966 ms` inclusive monotonic;
- `dependency_discovery=7515.262445 ms` inclusive monotonic;
- one header, one summary, `dropped_events=0`, `flush_failures=0`, no BootOptim trace errors.

Those are profile/inclusive telemetry, not an A/B performance result. Physical status for this diagnostic front is **sin evidencia física**; no laptop run is requested because there is no performance candidate.

## Risks

- **Classloader/module:** the transformed `fml_loader` class must resolve the SERVICE helper. Exact-pack is the deciding functional gate.
- **Callback semantics:** instrumentation only observes immediately before/after the existing `constructMod()` call and reads the already-resolved dependency graph. It does not wrap/replace executors, futures, callbacks or ordering.
- **Exception trace validity:** successful startup yields balanced pairs. A fatal exception inside `constructMod()` can leave the diagnostic task open; that makes the trace invalid rather than changing FML failure semantics.
- **Render/visual:** no Minecraft render/OpenGL method is targeted and no work changes threads. Hosted llvmpipe is functional evidence only.
- **Interpretation:** `gatherAndInitializeMods` is inclusive and overlaps child construction tasks. Do not sum it with child task wall or infer TTMM savings from event counts.

## Next decision

If exact-pack validates one writer and the new boundaries, inspect the dependency-chain path ending at the `boot_optim` construction task and the background-scan barrier duration. The next invasive research target should be chosen only from that causal evidence; do not optimize Discovery/containers/events merely because an inclusive interval is large.

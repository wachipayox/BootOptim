# FML post-discovery causal trace — 2026-09-08

Status: **DIAGNOSTIC / STACKED / HOSTED VALIDATED (FIRST BOUNDARY ONLY)**

This work is diagnostic infrastructure only. It does not claim a TTMM improvement and must not be merged as a production optimization merely because CI is green.

## Base and stacking

Integration authority at assignment start: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This branch is intentionally stacked on PR #201 head `a957b77c58c52edf1bccf68c5bc394cd84432e51`, which itself is stacked on trace-core PR #200. Neither #200 nor #201 is integrated in the authority SHA above. After #200/#201 are resolved, this branch must be rebased so its diff contains only the post-Discovery boundary, its tests and this record. The stacked diff must not be presented as integrated production code.

PR #198's dual-writer architecture is not reused. `StructuredBootTrace` schema v1 remains the single writer/schema source from #200, and #201's rule that SERVICE/main must physically contain the shared trace source remains intact.

## Exact 1.21.1 sequence mapped before instrumentation

The exact pack uses NeoForge `21.1.248`, FancyModLoader `4.0.43` and ModLauncher `11.0.5`. The public NeoForge `1.21.1` branch gives the relevant client/common boundary directly:

1. ModLauncher SERVICE initialization runs BootOptim's early transformation service.
2. NeoForge root candidate locators run; #201 brackets these as `root_mod_discovery`.
3. dependency locators/JarJar resolution run; #201 brackets these as `dependency_discovery`.
4. ModLauncher finishes the launch transition and game-layer bootstrap. This part is still not structurally traced by BootOptim.
5. NeoForge game-layer class `net.neoforged.neoforge.internal.CommonModLoader.begin(...)` obtains the sync executor and directly calls `ModLoader.gatherAndInitializeMods(syncExecutor, ModWorkManager.parallelExecutor(), periodicTask)`.
6. FML `gatherAndInitializeMods` consumes the resolved loading list, waits for `FMLLoader.backgroundScanHandler.waitForScanToComplete(periodicTask)`, validates features, builds `ModContainer`s, publishes the `ModList`, and calls `constructMods`.
7. `constructMods` invokes `dispatchParallelTask("Mod Construction", ...)`. FML creates one future per sorted mod and gates each future on the futures of that mod's declared loading dependencies before calling `modContainer.constructMod()` and dispatching `FMLConstructModEvent`.
8. The BootOptim regular mod entrypoint therefore occurs inside the `gatherAndInitializeMods` interval. After the method returns, `CommonModLoader.begin` proceeds to Registry initialization.

Later lifecycle events, resource reload, ModelBakery, FancyMenu, MCEF and rendering are intentionally out of scope.

## Hook selection: what was discarded and what survived

The first implementation attempted to transform `net.neoforged.fml.ModLoader` itself to expose the background-scan barrier and per-mod construction tasks. Hosted runs disproved that hook assumption: FML `ModLoader` is loaded as `MC-BOOTSTRAP/fml_loader` and the structured trace contained only the Discovery pairs. A broadened bytecode matcher still produced no new trace events and one diagnostic smoke entered a broken FML state. Those attempts are discarded and are not evidence for a safe FML-internal hook.

The validated implementation therefore uses the smallest game-layer boundary that is both causal and transformable:

- target: `net.neoforged.neoforge.internal.CommonModLoader`;
- call site: the direct invocation of `net.neoforged.fml.ModLoader.gatherAndInitializeMods(... )V`;
- before call: begin task `fml_gather_and_initialize_mods`;
- after normal return: end the same task;
- dependency: #201's `dependency_discovery` task ID.

The transformer matches the stable owner/name of the direct call and a void descriptor rather than synthetic lambda numbering. An ASM unit test asserts the exact ordering `begin hook -> original gather call -> end hook` and separately asserts that an FML `ModLoader` class is ignored.

There are deliberately **no** background-scan barrier or per-mod construction events in this PR. The public source proves those internal causal structures exist, but no safe runtime interception point for the pinned SERVICE/bootstrap-loaded FML class has yet been demonstrated. Inventing those events from inclusive timing would be false DAG precision.

## Classloader and execution semantics

`EarlyStartupProbeService.transformers()` returns the diagnostic transformer only when `StructuredBootTrace.global().isEnabled()`. In default/off mode it returns an empty list, so no FML/NeoForge diagnostic transformer is installed. Benchmark continues to rely on trace-core's no-clock/no-buffer/no-JSON per-event contract.

The injected call is in a transformed NeoForge game-layer class and resolves the helper supplied by the bootstrap/SERVICE output established by #201. Exact-pack profile smoke is the deciding proof that this cross-layer reference resolves without creating a second trace global/writer.

The validated events begin/end on the existing `Render thread`; instrumentation does not move this work or any OpenGL/render-thread work to a worker. `gatherAndInitializeMods` itself launches/waits on FML parallel work internally, so its duration is **inclusive wall time**, not CPU time and not a summable task total.

## Hosted validation

Profile contract:

```text
-Dboot_optim.bootTrace.mode=profile
-Dboot_optim.bootTrace.origin=hosted_exact_pack
-Dboot_optim.bootTrace.endpoint=main_menu
```

Validated code commit: `1a29ef4a872ac35718ed420736e426f854d41321`.

Hosted gates:

- Build `34278378726`: success; includes the ASM transformer tests and packaged bootstrap validation.
- Startup Benchmark `34278378600`: success with default trace/off behavior.
- Exact Pack Startup Benchmark `34278378457`: success, reached `main_menu`; artifact `10076742405` (`exact-pack-result-smoke-1`).

Exact-pack result:

- origin: `hosted_exact_pack`;
- endpoint: `main_menu`;
- `main_menu_ms=89888` (process-to-menu smoke wall marker; not A/B evidence);
- `mod_entrypoint_ms=30631` (process-to-existing BootOptim mod entrypoint marker);
- `bootoptim_mixin_errors=0`;
- exact resource-selection contract valid; one reload; expected and observed resource-pack lists identical;
- JSONL: exactly 1 schema-v1 header, 1 summary, 6 events;
- `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`;
- task pairs balanced: 3 begin / 3 end; no duplicate writer/schema.

Structured monotonic intervals from that JSONL:

| Boundary | Metric type | Inclusive wall |
| --- | --- | ---: |
| `root_mod_discovery` | monotonic inclusive task wall | 528.940587 ms |
| `dependency_discovery` | monotonic inclusive task wall | 7301.348133 ms |
| end `dependency_discovery` -> begin `fml_gather_and_initialize_mods` | **untraced temporal gap**, not a task | 18523.504998 ms |
| `fml_gather_and_initialize_mods` | monotonic inclusive task wall | 5417.190367 ms |

The gather begin event is task ID 3 with `dependency_ids:[2]`; task 2 is `dependency_discovery`. This proves the intended structural predecessor edge in the single trace. It does **not** prove that the 18.524 s between those timestamps is idle or attributable to either task.

For comparison only, inherited #201 profile smoke `34273366662` reported `main_menu_ms=88782`, `mod_entrypoint_ms=30020`, `root_mod_discovery=557.368966 ms` inclusive and `dependency_discovery=7515.262445 ms` inclusive. The two smokes are not an A/B performance experiment; differences must not be interpreted as a speedup/regression from this diagnostic hook.

## Critical-path interpretation

The trace now has a real dependency edge from Discovery into the first NeoForge/FML mod-initialization boundary, but it still does **not** cover a continuous critical path from Discovery to `mod_entrypoint`:

- the 18.524 s interval after dependency Discovery and before `CommonModLoader.begin`/gather is structurally unexplained;
- the 5.417 s gather interval is inclusive over FML's background-scan join, container creation and dependency-gated parallel construction;
- no per-mod task IDs exist yet, so task-sum and per-mod critical-chain attribution inside gather are unavailable;
- inclusive Discovery/gather values must not be added and called savings.

Accordingly, this PR establishes a reliable first causal edge and a measured coverage gap, not a complete boot DAG and not a performance candidate.

## Risks

- **Classloader/module:** validated for this one game-layer -> bootstrap helper reference by exact-pack profile; FML-internal SERVICE transformation remains unproven and is not attempted.
- **Callback/scheduling:** no executor, future, callback, dependency ordering or classloading order is replaced or wrapped. The hook only observes immediately before/after the existing direct call.
- **Exception trace validity:** if `gatherAndInitializeMods` throws fatally, its task can remain open because the hook intentionally does not alter exception control flow. That invalidates the diagnostic pair rather than changing FML semantics.
- **Render/visual:** the call is observed on the existing Render thread; no rendering/OpenGL method is targeted, no work changes threads, and the exact-pack resource-selection contract remained valid.
- **Interpretation:** gather wall includes parallel child work and waits; it is neither CPU sum nor an optimization estimate.

Physical status: **sin evidencia física**. No laptop run is requested because this is diagnostic infrastructure and there is no performance candidate to arbitrate.

## Next decision

Instrument the **18.524 s uncovered transition** between the end of dependency Discovery and the entry into `CommonModLoader.begin`, using the smallest ModLauncher/NeoForge launch/game-layer boundaries that form actual causal transitions. Do not expand into per-mod FML internals until a safe hook for the bootstrap/SERVICE-loaded FML implementation is demonstrated. Only after that gap is structurally partitioned should the program decide whether an invasive NeoForge/ModLauncher restructuring can move the menu critical path.

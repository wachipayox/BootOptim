# External resource-reload listener critical path — 2026-09-08

Status: **PROFILED / NO-GO FOR GENERIC OVERLAP**

PR: #184. Runtime evidence commit: `93d59a14fbfc337c4eaf29dec19e1c47d838c03a`, based exactly on `agent/integration-current` `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Question and boundary

This pass asks what remains on the initial client resource-reload critical path outside the structural `ModelManager` / `ModelBakery` work already covered by #47, #138 and #141, and whether any listener/apply ordering is demonstrably false and safe to overlap.

The real-class replay from #183 is the semantic boundary, not a TTMM benchmark. Its fixture executes real 1.21.1 `BlockModel.fromStream`, parent resolution, texture/material lookup and ordinary `ElementsModel` creation on a bounded exact-pack sample. Across its two isolated runs the cold-ish stock pass measured roughly 34–52 ms parse wall, 2.7–7.5 ms parent-resolution wall, 3.4–6.7 ms texture/material wall and about 0.6–0.7 ms ordinary `ElementsModel` wall. Those harness values are not additive to startup. Sprite decode/stitch/upload, final bake output, GL and mod callbacks are explicitly outside that replay.

NeoForge 1.21.1 additionally posts `ModelEvent.ModifyBakingResult` during `ModelManager.loadModels` and `ModelEvent.BakingCompleted` during `ModelManager.apply`. Those are arbitrary mod callback boundaries even though they are nested inside the ModelManager listener, so this diagnostic records them separately from #183's structural model work.

## Diagnostic

Opt-in property:

```text
-Dboot_optim.profileReloadListenerCriticalPath=true
```

Markers:

```text
BOOTOPTIM_RELOAD_LISTENER_PATH
BOOTOPTIM_MODEL_CALLBACK
```

The probe wraps only the existing `PreparableReloadListener.PreparationBarrier` and observes the existing listener future. It does **not** wrap/count executor tasks and never sums `ProfiledReloadInstance` listener durations. For each listener it records:

- `prepare_done_ms`: first semantic barrier arrival;
- `turn_ready_ms`: completion of the stock ordered barrier future;
- `observed_post_turn_ms`: listener-future completion minus observed turn;
- `serial_slot_ms`: **exclusive ordered slot**, from this listener's turn to the next listener's turn, or from the final turn to `allDone`;
- `global_wait_ms` and `order_wait_ms` for attribution only.

The summary accepts the serial-slot sum only when turns are monotonic. This makes the reported slot total non-overlapping critical-path wall rather than an inclusive/task-sum proxy. When the property is false, the mixin delegates the original state factory with the original barrier, futures and executors; no runtime behavior is changed.

## Hosted exact-pack smoke

Origin: GitHub hosted exact-pack Linux/Xvfb/llvmpipe surrogate, Oracle JDK 25.0.4, `ActiveProcessorCount=4`, exact pinned software pack. Endpoint: BootOptim `main_menu`. This is an attribution run, not a hardware performance population and not an optimization A/B.

Actions run `34175380705`, artifact `10037128690`, artifact digest `sha256:ca9b7ad632b27ae4210fa337658cf39e5e66838286698d3421a3ab7fb4568563`.

Validity:

- exact resource-selection contract: valid;
- blocks atlas: `8192x8192x2`;
- BootOptim Mixin errors: `0`;
- `main_menu`: `91.126 s` process-origin startup;
- reload marker -> FancyMenu finish: `42.375 s` (enclosing metric, not listener sum).

Main reload trace:

```text
listeners                         72
allPreparations                  28.727523 s
allDone                          42.285325 s
serial tail                      13.557802 s
exclusive serial-slot sum        13.557622 s
unaccounted                       0.000180 s
turns monotonic                   true
ModelManager exclusive slot       0.474511 s
external exclusive slots         13.083111 s
```

The preparation gate is still `ModelManager` at `28.724775 s`, within ~2.75 ms of `allPreparations`. Therefore preparation-heavy external listeners are hidden unless they move that gate; only the ordered slots below are defensible serial wall.

Largest exclusive slots:

| listener | hosted exclusive serial wall | interpretation |
|---|---:|---|
| final `Minecraft$Anonymous...` | 4161.770 ms | correlates essentially 1:1 with current FancyMenu panorama preload (`4148.750 ms`); explicitly out of scope here |
| `Minecraft$$Lambda...` index 25 | 3590.560 ms | real serial cost, but anonymous callsite not yet source-attributed; no threading claim allowed |
| `EntityRenderDispatcher` | 2153.135 ms | renderer-map reconstruction plus NeoForge `EntityRenderersEvent.AddLayers`; arbitrary mod compatibility boundary |
| `Minecraft$$Lambda...` index 26 | 852.055 ms | real serial cost, but anonymous callsite not yet source-attributed |
| NeoForge `ClientModLoader::onResourceReload` | 572.029 ms | post-barrier `finishModLoading`; mod lifecycle work on `ModWorkManager.parallelExecutor`, not a free reorder target |
| `ModelManager` | 474.511 ms | internal listener apply slot; separate from external-listener campaign |
| vanilla `Shader Loader` | 425.480 ms | render/shader work; render-thread/GL safety boundary |
| Veil shader manager | 293.468 ms | shader subsystem, not safe generic off-thread work |
| `BlockEntityRenderDispatcher` | 252.902 ms | renderer/provider reconstruction; mutable model/render state |
| Flywheel programs reloader | 166.733 ms | shader/program subsystem |
| `LevelRenderer` | 32.981 ms | hosted llvmpipe only; physical #85 remains the relevant hardware observation (~2.061 s current wall), with GL/post-chain restrictions |

After removing the explicitly excluded FancyMenu-correlated final slot, **8.921341 s** of hosted external serial wall remains. The two unattributed Minecraft lambdas alone own **4.442615 s**; `EntityRenderDispatcher` owns **2.153135 s**. These are exclusive slots, so these values may be compared with the 13.558 s tail; they are not sums of overlapping listener durations.

NeoForge model callbacks in the same run:

```text
ModelEvent.ModifyBakingResult   371.626 ms wall, Worker-ResourceReload-2
ModelEvent.BakingCompleted        5.049 ms wall, Render thread
```

`ModifyBakingResult` occurs before the global preparation gate and is already contained in ModelManager preparation; it is **not** an extra 372 ms tail saving. `BakingCompleted` is contained in ModelManager's 474.511 ms serial apply slot. Both remain arbitrary mod callbacks and cannot be moved/reordered generically from this evidence.

## Why no overlap candidate is implemented

The run proves serial wall exists, but it does **not** prove a false dependency that BootOptim can remove safely:

1. `SimpleReloadInstance` already overlaps preparation. The remaining slots are the ordered apply chain; changing that order changes the synchronization contract rather than merely exposing existing parallelism.
2. `EntityRenderDispatcher` creates entity/player renderer maps and NeoForge immediately posts `EntityRenderersEvent.AddLayers` with those mutable maps and the provider context. Providers can use `ResourceManager`, `ModelManager`, entity layers and mod state. Running it early could observe pre-commit ModelManager state; running it concurrently with later applies can race arbitrary mod callbacks. Generic purity/thread-safety is not declared.
3. `BlockEntityRenderDispatcher` similarly reconstructs renderer/provider state. Sharing/memoizing returned mutable `ModelPart` trees or moving provider callbacks off their established thread is not semantics-preserving.
4. NeoForge's `ClientModLoader` listener runs `finishModLoading` after the barrier and can execute arbitrary mod lifecycle work before reloading options. It is an ordering/compatibility boundary, not a batching candidate.
5. Shader/Veil/Flywheel/LevelRenderer slots include render-thread or GL/program work. The project rule forbids moving those operations off the render thread.
6. Deferring renderer construction until world entry would merely shift a startup cost into gameplay and would violate the no-gameplay-regression goal unless every first consumer/callback contract were separately proven.
7. The two largest non-FancyMenu anonymous `Minecraft` lambdas are genuinely interesting, but their callsites are not identified by the current listener name. Implementing around them before source/callsite attribution would repeat the mistake this diagnostic was designed to prevent.

Consequently there is no behavior-changing candidate, no kill-switch optimization to promote, and no meaningful 3x A/B to run. A 3x `probe=true/false` campaign would measure diagnostic overhead, not a proposed saving. Build, normal startup smoke, and exact-pack diagnostic smoke are the appropriate gates for this branch.

## Build/smoke evidence

Runtime commit `93d59a14fbfc337c4eaf29dec19e1c47d838c03a`:

- Build: Actions `34175380763` — success; artifact `10037066351`, digest `sha256:0869505472a7a63c7d1fbae45a3d6ffd8f9223387f2addd5d04a399a4d07d0ac`.
- Generic startup smoke: Actions `34175380738` — success to menu; artifact `10037074876`, digest `sha256:786d95212fb903961d772a68dffabb972d0eb66509798203155e8a4d691b49d1`.
- Exact-pack attribution smoke: Actions `34175380705` — success; artifact `10037128690`, digest above.

## Decision

**Do not move any change from this PR into the modpack build.** Keep #184 diagnostic-only/draft. The front is closed against generic listener parallelism, global apply reordering, renderer construction off-thread, and generic mod-callback overlap.

No laptop run is requested. Hosted already establishes large serial slots, but there is no safe mechanism to A/B on hardware. The historical physical ceilings for renderer listeners show that hardware scaling can be material; that fact does not create a valid implementation by itself.

## Reopening criteria

Reopen only with one of these materially new premises:

1. Add a **callsite-only** diagnostic for Minecraft listener indices 25 and 26 (for example capture the synthetic `Minecraft.lambda$new$...` implementation identity without changing scheduling). If either is a pure, menu-unneeded operation with a source-proven first-consumer boundary, design a separate narrow defer/prepare-commit candidate.
2. For `EntityRenderDispatcher`, inventory every exact-pack provider and every `EntityRenderersEvent.AddLayers` subscriber and prove a bounded subset has immutable inputs, no GL/render-thread affinity, no reads of uncommitted ModelManager state, and no cross-listener side effects. Only that subset may be considered for prepare/commit splitting; never move the event wholesale.
3. A mod upstream exposes an explicit thread-safe two-phase reload API (`prepare` immutable data, `apply` main-thread commit) that preserves callback order. BootOptim may then exploit the declared contract rather than guessing purity.
4. A later version removes or changes one of these ordering/callback contracts materially.

The first reopening probe should stay hosted/exact-pack. A physical gate becomes justified only after such a source-proven candidate shows coherent hosted critical-slot or TTMM leverage; otherwise do not spend laptop starts.

# FML gather internals — 2026-09-09

Status: **DIAGNOSTIC / STACKED / HOSTED EXACT-PACK VALIDATED**

This experiment attributes the inclusive wall inside `fml_gather_and_initialize_mods`. It is not an optimization, an A/B, or a claim of saved time.

## Base and scope

Integration authority at assignment start: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

The diagnostic branch is intentionally stacked on PR #212 head `9c1d6f3d7b25aeed099eee4b78c1818d94625b8d`, which carries the #200/#201/#202 structured-trace stack and the validated single-writer regular-mod bridge. The implementation here continues to write through the bootstrap-owned `StructuredBootTrace`; it does not introduce another writer, schema, sequence, or output file.

Out of scope: Discovery/ModLauncher before `CommonModLoader.begin`, post-gather registry/config/setup phases, resource reload/ModelManager, gameplay, GL/render work, and any performance optimization.

Pinned project environment: Minecraft `1.21.1`, NeoForge `21.1.248`.

## Source map for `gatherAndInitializeMods`

The transformable GAME-layer boundary is `net.neoforged.neoforge.internal.CommonModLoader.begin`, which obtains the stock sync executor and invokes:

`ModLoader.gatherAndInitializeMods(syncExecutor, ModWorkManager.parallelExecutor(), periodicTask)`.

FML `ModLoader.gatherAndInitializeMods` then executes, in order:

1. consume current `LoadingModList` issues and fail if already invalid;
2. register the Java-version feature and block in `backgroundScanHandler.waitForScanToComplete(periodicTask)`;
3. create the `ModList`, validate declared features, and fail if invalid;
4. synchronously build and validate `ModContainer`s through each language loader, then publish them to `ModList`;
5. create the `Mod Construction` deferred-work queue;
6. call `dispatchParallelTask("Mod Construction", ...)`;
7. after that gate opens, run `Mod Construction: Deferred Queue` through the supplied sync executor and wait for it;
8. return to `CommonModLoader.begin`.

For JavaFML containers, the serial container-build portion is not empty bookkeeping: `FMLModContainer` builds the mod event bus, resolves the game module, and `Class.forName(layer, entrypoint)` loads each mod entrypoint class. The later `constructMod()` path resolves the public constructor, invokes it reflectively, then performs `AutomaticEventSubscriber.inject` before the enclosing `ModLoader` dispatches `FMLConstructModEvent`.

`dispatchParallelTask` is dependency-gated rather than a flat fan-out. It walks the sorted mod list, resolves each declared loading dependency to an already-created future, builds `CompletableFuture.allOf(depFutures).handleAsync(..., parallelExecutor)`, and then executes the container task. Dependents of a failed future short-circuit with `DependentFutureFailedException`. FML gathers the resulting futures and the caller waits with 50 ms timed waits while repeatedly invoking the existing `periodicTask`.

The background-scan join has no progress bar or post-completion callback. Its loop calls the ticker *before* `awaitTermination(50 ms)` and, when termination is observed, returns directly into ModList/container work. Therefore the existing callback/progress APIs do not expose a causal boundary between scan completion and serial container construction.

## Hook decision

Direct transformation of `net.neoforged.fml.ModLoader` remains a no-go: #202 demonstrated that this class lives in `MC-BOOTSTRAP/fml_loader` and ordinary BootOptim transformation-service matching does not reliably instrument it. This experiment does not repeat or widen that matcher.

The safe observable boundary is the already validated GAME-layer `CommonModLoader` callsite. The call descriptor is pinned exactly to:

`(Executor, Executor, Runnable)void`.

The transformer fails open unless exactly one owner/name/descriptor match exists. At that callsite it leaves both executors untouched and replaces only the operand-stack value for the third argument with a trace-only delegating `Runnable`. The original local callback is not rewritten, so later registry/config/setup calls continue receiving the original callback object.

The wrapper invokes the original `periodicTask.run()` exactly once, first, on the same thread. Only after normal callback return does it read `StartupNotificationManager.getCurrentProgress()` and observe the exact stock progress names. It emits no event on ordinary ticks and no event per mod.

Observed transitions create three coarse child tasks under the existing outer gather task:

- `fml_gather_pre_construction`: gather entry until the first observed `Mod Construction` progress bar. It contains scan join, ModList/feature checks, synchronous container/language-loader build and publication, creation of the construction work queue, and entry into `dispatchParallelTask` through its first stock periodic callback.
- `fml_mod_construction_parallel`: begins immediately after that first callback. In FML source this is **before** creation of the `modFutures` map, so this bucket includes the small serial dependency-future graph/setup plus the overlapping dependency-gated worker execution and caller wait. The name denotes the enclosing `Mod Construction` parallel dispatch gate; it is not pure worker CPU.
- `fml_mod_construction_deferred_queue`: begins when the stock `Mod Construction: Deferred Queue` progress bar is observed and ends on normal gather return. The work itself remains scheduled on the stock sync executor.

The progress bars are existing FML state created by `dispatchParallelTask`/`waitForTask`; BootOptim does not create, complete, reorder, or mutate them.

A finer split of `fml_gather_pre_construction` is currently **no-go** with the allowed premises: the scan join exposes no completion callback/progress meter, there is no public lifecycle event between scan completion and serial container construction, and the implementation methods that would provide exact boundaries live in the non-transformable FML layer. Wrapping language loaders, futures, executors, or scan tasks would change ownership/classloading/scheduling risk and is outside this diagnostic contract.

## Semantic and overhead constraints

- default/off: no diagnostic transformer is installed, inherited from #202/#212;
- detailed observation: profile/development only; benchmark mode keeps only the outer gather marker and does not snapshot progress state;
- callback: original `Runnable` invoked once before observation; callback exceptions propagate before instrumentation runs;
- executors/futures/dependency graph: never wrapped, replaced, awaited separately, or reordered;
- callback order/thread: unchanged; observation runs after the existing callback on the same caller thread;
- classloading: no per-mod instrumentation and no transformer target outside the GAME-layer `CommonModLoader` callsite;
- trace ownership: bootstrap/SERVICE remains the sole `StructuredBootTrace` owner/writer;
- event volume: gather begin/end plus at most three coarse child task begin/end pairs; no per-mod emission.

## Hosted exact-pack evidence

Instrumentation head validated: `6a3d7817cfb1f2705e89f8c9cecae3cec3e654bb`.

Successful hosted run: Actions `34294584017` (`Agent82 Exact Pack Validation`), artifact `10082706038`. The auxiliary validation used the same pinned exact-pack release asset/SHA, JCEF commit, Oracle JDK 25.0.4 runtime, llvmpipe display path, profile origin `hosted_exact_pack`, and endpoint `main_menu`; unlike the shared workflow it downloads a cold fixture directly instead of requiring a pre-existing cache entry.

Result:

- `main_menu_ms`: **89,666 ms**;
- outer `fml_gather_and_initialize_mods`: **5,146.160 ms** inclusive wall on Render thread;
- `fml_gather_pre_construction`: **104.218 ms**;
- `fml_mod_construction_parallel`: **5,039.885 ms**;
- `fml_mod_construction_deferred_queue`: **2.021 ms**;
- child coverage: **5,146.123 ms**, leaving only **0.037 ms** of transition/hook gap inside the parent;
- `bootoptim_mixin_errors`: **0**;
- resource-selection check: **valid**, one reload, expected resource packs exactly matched observed;
- trace header: schema v1, mode `profile`, origin `hosted_exact_pack`, endpoint `main_menu`;
- trace summary: `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`;
- six task begins and six task ends were balanced with valid parent/dependency references.

The exact-pack run therefore establishes that, for this hosted sample, almost the entire ~5.146 s gather wall is spent inside the `Mod Construction` dispatch gate. It **does not** establish 5.040 s of CPU work, identify the critical mod/dependency chain, or imply an equivalent optimization opportunity: worker tasks overlap, dependency waits are included, and the bucket includes serial future-graph setup before the waits.

The repository's shared `Exact Pack Startup Benchmark` check for this head did not launch Minecraft: both the initial attempt and a failed-jobs rerun aborted at `actions/cache/restore` for `exact-pack-2026-09-02-v1-7f586ecd90497a4d-linux` with `fail-on-cache-miss`, even though the fixture job independently downloaded and SHA-verified the pinned ZIP. This is a harness/cache availability failure, not runtime evidence. It is intentionally not counted as a profile.

## Tests / other hosted gates

The Build workflow passed for the instrumentation head, including the transformer tests. Unit coverage asserts:

- exact injection order `begin gather -> wrap periodic callback -> original gather -> end gather`;
- exact FML progress names map only to the construction/deferred stages;
- descriptor drift fails open;
- multiple candidate callsites fail open;
- direct FML `ModLoader` remains ignored.

The normal hosted Startup Benchmark also passed. No laptop run was requested or used.

## Decision

**Keep as diagnostic attribution only.** The safe GAME-layer hook exists and is validated, but the result is not an optimization candidate by itself. The next meaningful investigation, if separately authorized, would need a safe way to identify the slow dependency chain or aggregate mod-family construction work without per-mod trace overhead or lifecycle/scheduling changes. Under the current constraints there is no safe finer boundary for scan-vs-container work inside the 104 ms prefix, and no basis to claim the 5.040 s parallel-gate wall as recoverable startup time.

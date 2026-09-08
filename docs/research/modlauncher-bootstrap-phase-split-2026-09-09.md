# ModLauncher -> Minecraft Bootstrap phase split — 2026-09-09

Status: **ACTIVE DIAGNOSTIC / NO PERFORMANCE CLAIM**

## Scope and authority

Authority refreshed before branching: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This work is intentionally stacked on diagnostic PR #205 (`852ada6098bb0060f02360295a96f5ac159be784`), itself stacked on #203 -> #202 -> #201 -> #200. It is not integrated production code.

The only target is #205's cross-thread phase `modlauncher_transformers_to_minecraft_bootstrap`, measured in hosted exact-pack profile `34282685222` as **10072.921316 ms inclusive**, from BootOptim's existing SERVICE `EarlyStartupProbeService.transformers()` callback on `main` to the exact `Bootstrap.bootStrap()` entry on `pool-8-thread-1`. It is a phase interval, not a lexical task, not CPU, not critical-path task wall and not a savings budget.

The separate ~1.429 s Discovery-end -> SERVICE-callback residue and the post-Bootstrap residue are out of scope.

## Causal map inherited from #205

Exact ModLauncher `11.0.5+main.901c6ea8` ordering mapped by #205 is:

1. transformation-service scan completion and GAME-resource registration;
2. `initialiseServiceTransformers()` / each service `transformers()` callback;
3. launch-plugin scan-result processing and launch-target validation;
4. GAME transforming classloader/module-layer construction;
5. TCCL switch and launch-service entry;
6. game-layer class loading/transformation, Mixin and related startup work;
7. Minecraft `Bootstrap.bootStrap()` entry.

#205 begins its phase at step 2 after BootOptim's callback is entered, so this investigation does not reopen the earlier post-Discovery residue.

## Hook audit and no-go boundaries

Several conceptually attractive boundaries are not acceptable BootOptim hooks here:

- **Per-service transformer registration:** BootOptim only owns its own `ITransformationService.transformers()` callback. Wrapping other services would require modifying already-active ModLauncher/SERVICE internals or replacing service dispatch. No such hook is introduced.
- **GAME module-layer/classloader creation:** `Launcher`, `TransformationServicesHandler`, `ModuleLayerHandler` and the transforming-classloader construction path are already executing in launcher/bootstrap layers before an ordinary transformation-service transformer can safely target them. A direct transformer would be a fragile classloading premise, not a diagnostic observation.
- **Launch-service entry:** registering an additional launch plugin/service would itself alter service discovery/loader state even when tracing is off, violating the zero-diagnostic-installation contract. Patching `LaunchServiceHandler` has the same already-active bootstrap-layer problem.
- **Broad MC-BOOTSTRAP/FML matcher:** explicitly rejected by #202/#203/#205 evidence; `ModLoader` is not reopened.
- **Log timestamps:** useful corroboration only. They are not emitted as structured spans and are not converted into task/phase durations.

The project already has historical Mixin pipeline attribution (#41/#42/#48; see `docs/research/mixin-pipeline.md`), but those measurements are from a different diagnostic campaign and cannot be inserted numerically into this hosted structured phase as if they were same-run causal spans.

## Minimum safe split

PR #203/#205 already contains a strict transformer for exactly `net.minecraft.server.Bootstrap` / `bootStrap()V`. Hosted exact-pack proved that target fires. The transformer fails closed unless there is exactly one matching method, an executable instruction and at least one normal `RETURN`.

Agent 72 adds no target and does not broaden that matcher. After all strict checks succeed, immediately before the existing diagnostic bytecode mutation, the transformer calls `ModLauncherTransitionTraceHooks.minecraftBootstrapTransformAccepted()`.

That literal edge divides the existing parent phase into two child phase pairs:

- `modlauncher_transformers_to_minecraft_bootstrap_transform_accept`: BootOptim SERVICE `transformers()` callback -> strict Bootstrap transform acceptance;
- `minecraft_bootstrap_transform_accept_to_entry`: strict Bootstrap transform acceptance -> injected `Bootstrap.bootStrap()` entry.

The parent `modlauncher_transformers_to_minecraft_bootstrap` phase remains unchanged. The child phases are deliberately named after observable edges rather than claiming that either interval is exclusively GAME-layer construction, Mixin, ASM writing, classloading or scheduler wait. Transformer ordering relative to other transformation services can affect which of those mechanisms lie on either side of the accepted-edge timestamp.

Both child intervals use `phase_begin/end`, not `task_begin/end`, because the enclosing route can cross threads and schema-v1 tasks are thread-lexical. They therefore remain outside producer CPU, inclusive task-wall and task dependency critical-path sums.

## Safety invariants

- `-Dboot_optim.bootTrace.mode=off` still returns zero diagnostic transformers from `EarlyStartupProbeService.transformers()`; this change cannot run in off mode because the inherited Bootstrap diagnostic transformer is not installed.
- No new service provider, launch plugin, transformer target or matcher is added.
- No scheduling, executor, thread, callback, classloading/module order, Mixin order, render/GL work or gameplay behavior is changed.
- All trace hooks remain fail-open.
- Existing exact Bootstrap target/method/normal-return rejection semantics remain unchanged.

## Tests

`MinecraftBootstrapTraceTransformerTest` retains:

- exact target/method matching;
- injected `beginBootstrap -> body -> endBootstrap` order;
- every normal-return closure;
- rejection of missing normal returns, duplicate matching methods and wrong target classes;
- SERVICE callback task ordering and Bootstrap-entry transition close ordering.

It additionally inspects the compiled transformer bytecode and asserts that `minecraftBootstrapTransformAccepted()` is called before the first existing `InsnList.insertBefore` mutation.

## Validation gate

Required before disposition:

1. build/package and normal startup;
2. hosted exact-pack profile to `main_menu`;
3. JSONL: one header, one summary, balanced parent + child phase pairs and all inherited task pairs, contiguous sequence, zero drops/errors/failures;
4. report the two child intervals separately as inclusive phases and verify that their measured boundaries tile the parent within event-timestamp overhead.

No A/B and no physical-laptop run are warranted because this is diagnosis, not an optimization candidate.

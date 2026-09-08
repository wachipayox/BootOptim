# Post-Bootstrap client transition trace — 2026-09-08

Status: **DIAGNOSTIC / PROFILE VALIDATION PENDING**

This branch adds no startup optimization and makes no TTMM claim. It only subdivides the approximately 3.191 s hosted exact-pack temporal gap observed by PR #203 between the normal return of `net.minecraft.server.Bootstrap.bootStrap()` and PR #202's begin hook immediately before `ModLoader.gatherAndInitializeMods(...)`.

## Base and stacking

Integration authority was refreshed as `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This diagnostic is intentionally stacked on PR #203 head `5995a3e7f914ae07483219ee1ccf947ed10b3365`, which is stacked on #202, #201 and trace-core #200. None of those diagnostics is integrated production code. After the lower stack is resolved, this branch must be rebased so its diff contains only the post-Bootstrap validation/client edges, tests, dependency adjustment and this record.

## Exact control map

NeoForge's client `Main` patch executes, in order:

1. `BackgroundWaiter.runAndTick(() -> Bootstrap.bootStrap(), ImmediateWindowHandler::renderTick)`;
2. `GameLoadTimesEvent.INSTANCE.setBootstrapTime(...)`;
3. `Bootstrap.validate()`;
4. `ClientModLoader.begin()`.

`BackgroundWaiter.runAndTick` submits `Bootstrap.bootStrap()` to a single-thread executor. The caller repeatedly executes the supplied tick, sleeps 50 ms, and only then re-checks `Future.isDone()`. After the worker has returned, the caller can therefore still be inside an already-started `ImmediateWindowHandler.renderTick()` and its following sleep before it observes completion. This explains why PR #203's worker-side `minecraft_bootstrap` end cannot by itself coincide with the caller's return from `BackgroundWaiter`.

`ClientModLoader.begin()` then registers Log4j shutdown, obtains/updates the early loading screen, runs `LanguageHook.loadBuiltinLanguages()`, creates the periodic-tick callback and delegates to `CommonModLoader.begin(periodicTick, false)`. `CommonModLoader.begin` obtains its sync executor and then directly invokes `ModLoader.gatherAndInitializeMods(...)`; PR #202 already wraps only that final direct call.

The FML `BackgroundWaiter`/`ImmediateWindowHandler` implementation remains deliberately untransformed. PR #202 established that bootstrap-loaded FML classes are not a safe target for this diagnostic lane, and this branch does not broaden that matcher.

## Minimal transformable boundaries

Two game-layer boundaries are added:

- `minecraft_bootstrap_validate`: exact `net.minecraft.server.Bootstrap.validate()V`, begin before first executable instruction and end before every normal `RETURN`. It depends on the existing `minecraft_bootstrap` task when present.
- `client_mod_loader_pre_gather`: exact `net.neoforged.neoforge.client.loading.ClientModLoader.begin()V`, from method entry to immediately before its unique delegation to inherited `begin(Runnable, boolean)`. It depends on `minecraft_bootstrap_validate` when present.

Inside the client preamble, the unique exact `LanguageHook.loadBuiltinLanguages()V` call is separately bracketed as child task `client_builtin_languages`. This is attribution only; no language work is moved or cached.

The client transformer requires one `begin()V`, one exact builtin-language call, one inherited common-begin call, and the language call to precede the common-begin call. The inherited static owner is accepted only as either `ClientModLoader` or `CommonModLoader`, the two legal explicit owners for this source-level call. Missing, duplicated, reversed or unrelated targets fail closed with no injection.

The gather dependency becomes:

`dependency_discovery -> minecraft_bootstrap -> minecraft_bootstrap_validate -> client_mod_loader_pre_gather -> fml_gather_and_initialize_mods`

with fail-open fallback to the nearest earlier emitted task ID if any new boundary is absent.

## What remains a temporal gap

The interval from worker-side `minecraft_bootstrap` end to render-thread `minecraft_bootstrap_validate` begin is intentionally left as a temporal difference rather than mislabeled as a task. From the source order it contains the tail of `BackgroundWaiter.runAndTick` after the worker completed plus `GameLoadTimesEvent.setBootstrapTime(...)`. Because the prohibited FML bootstrap class is not transformed, exact-pack timing must determine whether that tail owns the material residual.

Likewise, the short interval from `client_mod_loader_pre_gather` end to gather begin is the prefix of `CommonModLoader.begin` that obtains the sync/parallel executors. It is not assigned to the client task.

All reported values are inclusive monotonic wall intervals or temporal differences. They are not CPU time, not task-sum savings and not an optimization claim.

## Safety

- `boot_optim.bootTrace.mode=off` still returns zero diagnostic transformers.
- Profile hooks use the existing JDK-only `StructuredBootTrace` and catch diagnostic failures fail-open.
- No executor, scheduling, callback, thread, classloading order, render/GL path, resource reload, ModelBakery or gameplay behavior changes.
- No FML `MC-BOOTSTRAP` class is targeted.

## Validation contract

Required before disposition:

- build/package green;
- normal startup to `main_menu` with trace off;
- hosted exact-pack profile to `main_menu`;
- exactly one JSONL header and summary, balanced pairs, zero drops/flush/development-sink failures and zero trace errors;
- causal IDs in the expected order;
- report separately: bootstrap-end -> validate-begin temporal gap, validation inclusive wall, validate-end -> client-begin gap, client pre-gather inclusive wall, builtin-language child wall, client-end -> gather-begin gap, and gather inclusive wall.

No physical-laptop run is requested because this is diagnostic observability, not a performance candidate.

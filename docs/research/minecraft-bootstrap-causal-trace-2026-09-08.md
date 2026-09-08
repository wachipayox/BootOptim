# Minecraft bootstrap causal trace — 2026-09-08

Status: **DIAGNOSTIC / PROFILED OBSERVABILITY ONLY**

This branch adds no startup optimization and makes no TTMM claim. It only instruments a real game-layer boundary inside the previously untraced interval between FML dependency discovery and `CommonModLoader.begin -> ModLoader.gatherAndInitializeMods`.

## Base and stacking

Integration authority was refreshed as `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This diagnostic is intentionally stacked on PR #202 head `c0f995a7afa5480f3eff91f18decb5fd9f5bb619`, which is stacked on #201 and trace-core #200. Those PRs are not integrated production code. After the lower stack is resolved, this branch must be rebased so only the Minecraft-bootstrap edge, tests, causal dependency adjustment and this record remain.

## Code map: Discovery return to mod construction

The exact-pack uses Minecraft 1.21.1 / NeoForge 21.1.248 / FML 4.x. The relevant source and already-validated runtime boundaries are:

1. BootOptim's dependency-discovery end locator has `Integer.MIN_VALUE` priority, so it runs after normal `IDependencyLocator`s and closes the existing `dependency_discovery` trace task.
2. Control returns through FML/ModLauncher discovery and launch setup. ModLauncher then establishes/uses the game layer and invokes the Minecraft client launch target. This SERVICE/bootstrap transition is deliberately not transformed here; #202 already demonstrated that broad matching across bootstrap-loaded FML classes is unsafe and ineffective.
3. In game-layer `net.minecraft.client.main.Main.main`, NeoForge patches vanilla bootstrap to call `BackgroundWaiter.runAndTick(() -> Bootstrap.bootStrap(), ImmediateWindowHandler::renderTick)`, then records bootstrap time and executes `Bootstrap.validate()`.
4. The same patched method immediately calls `net.neoforged.neoforge.client.loading.ClientModLoader.begin()` after validation.
5. `ClientModLoader.begin()` performs its small client preamble (shutdown-hook registration, early-loading-screen handoff/progress, builtin-language loading) and delegates to `CommonModLoader.begin(...)`.
6. #202's transformable game-layer `CommonModLoader` hook starts `fml_gather_and_initialize_mods` immediately before its direct call to bootstrap-loaded `ModLoader.gatherAndInitializeMods(...)`.

Therefore the broad 17.5–18.5 s temporal hole contains at least three distinct regions and must not be represented as one invented task: (a) Discovery return -> entry into the patched Minecraft bootstrap call, (b) Minecraft `Bootstrap.bootStrap` + validation, and (c) `ClientModLoader.begin` preamble -> gather call.

Public upstream source used to establish the semantic boundary: NeoForge `patches/net/minecraft/client/main/Main.java.patch` and `src/client/java/net/neoforged/neoforge/client/loading/ClientModLoader.java`. Exact-pack runtime validation is required before treating the hook as present on 21.1.248.

## Minimal hook

`MinecraftBootstrapTraceTransformer` targets only `net.minecraft.client.main.Main` and only `main([Ljava/lang/String;)V`.

It requires exactly one of each ordered anchor:

- start: `INVOKESTATIC net/neoforged/fml/loading/BackgroundWaiter.runAndTick...`;
- end: later `INVOKESTATIC net/neoforged/neoforge/client/loading/ClientModLoader.begin()V`.

Only if both unique anchors exist in that order does it inject:

- `MinecraftBootstrapTraceHooks.beginBootstrapAndValidate()` immediately before `BackgroundWaiter.runAndTick`;
- `MinecraftBootstrapTraceHooks.endBootstrapAndValidate()` immediately before `ClientModLoader.begin`.

The structured task is named `minecraft_bootstrap_and_validate`. Its dependency is the existing `dependency_discovery` task ID. #202's gather hook now prefers this bootstrap task ID as its causal dependency and falls back to the Discovery task only if the new hook did not emit an ID. This yields the explicit chain:

`dependency_discovery -> minecraft_bootstrap_and_validate -> fml_gather_and_initialize_mods`

The interval is inclusive wall time. It includes the owner-thread wait/ticking around `Bootstrap.bootStrap` plus `Bootstrap.validate`; it is not CPU sum and is not claimed as savings.

## Safety and classloader boundary

- `boot_optim.bootTrace.mode=off` remains the default and `EarlyStartupProbeService.transformers()` returns an empty list, so no diagnostic transformer is installed.
- No executor, scheduling, callback, classloading order, OpenGL/render work, resource reload, ModelBakery or gameplay behavior is changed.
- `Main` is a game-layer Minecraft class, unlike `net.neoforged.fml.ModLoader` which #202 observed in `MC-BOOTSTRAP/fml_loader`. The transformer does not broaden into FML bootstrap classes.
- The matcher fails closed on missing, duplicated or reversed anchors. A NeoForge patch drift therefore produces a residual trace gap rather than a speculative transformation.
- Hook bodies catch diagnostic failures and fail open.

## Residual gaps

Even if the exact-pack hook succeeds, two temporal gaps remain intentionally unnamed until separately observed:

1. end of `dependency_discovery` -> injected begin immediately before `BackgroundWaiter.runAndTick`; this includes ModLauncher/FML return/layer-launch work and the pre-bootstrap portion of `Main.main`;
2. injected end immediately before `ClientModLoader.begin` -> #202 gather begin; this includes the `ClientModLoader.begin` preamble and entry into `CommonModLoader.begin`.

These residuals are temporal differences between real endpoints, not tasks, and must not be added to inclusive task durations as hypothetical savings.

## Tests and validation contract

`MinecraftBootstrapTraceTransformerTest` verifies hook ordering around synthetic exact anchors, rejection of missing/ambiguous anchors, and rejection of a non-`Main` target. #202's existing test continues to reject direct FML `ModLoader` instrumentation.

Required runtime gate for this diagnostic branch:

- package/build;
- normal startup to `main_menu` with tracing off;
- hosted exact-pack with `mode=profile`, `origin=hosted_exact_pack`, endpoint `main_menu`;
- one JSONL header and one summary, balanced task pairs, zero drops/flush/development-sink failures, and correct dependency IDs;
- report the measured bootstrap interval and both residual gaps separately.

No physical-laptop run is requested or relevant because this is observability, not a performance candidate.

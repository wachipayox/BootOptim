# Minecraft bootstrap causal trace — 2026-09-08

Status: **DIAGNOSTIC / PROFILED OBSERVABILITY ONLY**

This branch adds no startup optimization and makes no TTMM claim. It instruments one real Minecraft game-layer task inside the previously untraced interval between FML dependency discovery and `CommonModLoader.begin -> ModLoader.gatherAndInitializeMods`.

## Base and stacking

Integration authority was refreshed as `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This diagnostic is intentionally stacked on PR #202 head `c0f995a7afa5480f3eff91f18decb5fd9f5bb619`, which is stacked on #201 and trace-core #200. Those PRs are not integrated production code. After the lower stack is resolved, this branch must be rebased so only this Minecraft-bootstrap edge, tests, causal dependency adjustment and this record remain.

## Code map: Discovery return to mod construction

The exact-pack uses Minecraft 1.21.1 / NeoForge 21.1.248 / FML 4.x. Relevant source and runtime boundaries are:

1. BootOptim's `DependencyDiscoveryEndLocator` has `Integer.MIN_VALUE` priority and closes `dependency_discovery` after normal `IDependencyLocator`s. This is still inside `ModDiscoverer.discoverMods`; FML subsequently rebuilds the unique mod list, logs it and returns the discovery result.
2. FML loader setup then performs post-discovery work before the game launch: module descriptors, plugin/language-provider setup, game-content/module construction, transformer/classloader construction and Mixin finalization. The concrete implementation is loader-version sensitive and lives on the FML/bootstrap side, so this PR does not broaden a transformer into it.
3. NeoForge patches game-layer `net.minecraft.client.main.Main.main` to execute `BackgroundWaiter.runAndTick(() -> Bootstrap.bootStrap(), ImmediateWindowHandler::renderTick)`, then records the bootstrap time, calls `Bootstrap.validate()`, and finally calls `ClientModLoader.begin()`.
4. `ClientModLoader.begin()` performs its client preamble (shutdown-hook registration, early-loading-screen handoff/progress and builtin-language loading) and delegates to `CommonModLoader.begin(...)`.
5. #202's safe game-layer hook starts `fml_gather_and_initialize_mods` immediately before `CommonModLoader` directly calls bootstrap-loaded `ModLoader.gatherAndInitializeMods(...)`.

Thus the 17.5–18.5 s temporal gap is not one phase. It contains post-Discovery FML/layer setup, Minecraft bootstrap, and the short post-bootstrap path to gather. Inclusive task durations are not summable savings.

Public sources used for the map: FancyModLoader `ModDiscoverer` / `FMLLoader`, and NeoForge `patches/net/minecraft/client/main/Main.java.patch` plus `ClientModLoader.java`. Exact-pack runtime evidence below is authoritative for hook presence on the pinned workload.

## Failed first edge: patched `Main.main`

The first implementation targeted only `net.minecraft.client.main.Main.main([Ljava/lang/String;)V`, requiring unique ordered `BackgroundWaiter.runAndTick` and `ClientModLoader.begin()V` anchors. It was intentionally fail-closed.

Hosted exact-pack run `34280276018` reached `main_menu` with zero BootOptim Mixin errors, but its JSONL still contained only the inherited three task pairs (`root_mod_discovery`, `dependency_discovery`, `fml_gather_and_initialize_mods`). The `Main` hook emitted nothing. That target was discarded rather than broadened; the run does not prove whether the cause was target availability or exact bytecode-anchor drift.

The same run's normal log nevertheless showed vanilla bootstrap as a real subinterval: `Bootstrap` reported approximately 4.567 s. That motivated a narrower semantic edge instead of a broader matcher.

## Final minimal hook: exact `Bootstrap.bootStrap()V`

`MinecraftBootstrapTraceTransformer` targets only `net.minecraft.server.Bootstrap` and only the exact `bootStrap()V` method.

It fails closed unless exactly one such method exists and at least one normal `RETURN` is present. If matched, it injects:

- `MinecraftBootstrapTraceHooks.beginBootstrap()` before the first executable instruction;
- `MinecraftBootstrapTraceHooks.endBootstrap()` before every normal `RETURN`.

The structured task is `minecraft_bootstrap`. Its dependency is the existing `dependency_discovery` task ID. #202's gather hook now prefers the bootstrap task ID as predecessor and falls back to `dependency_discovery` only if this hook emitted no task. The observed chain is therefore:

`dependency_discovery -> minecraft_bootstrap -> fml_gather_and_initialize_mods`

The bootstrap interval is inclusive monotonic task wall on the existing worker chosen by NeoForge/FML (`pool-8-thread-1` in the validated run). No work is moved to a worker and no OpenGL/render operation is introduced by BootOptim.

## Safety and classloader boundary

- `boot_optim.bootTrace.mode=off` remains default; `EarlyStartupProbeService.transformers()` returns an empty list, so no diagnostic transformer is installed.
- No executor, scheduling, callback order, classloading order, OpenGL/render work, resource reload, ModelBakery, FancyMenu, MCEF or gameplay behavior is changed.
- `net.neoforged.fml.ModLoader` remains explicitly rejected as a transform target because #202 observed it in `MC-BOOTSTRAP/fml_loader`; this PR does not repeat the failed broad FML matcher.
- The new target is one Minecraft game-layer class/method and its matcher fails closed on method absence/ambiguity/no normal return.
- Hook bodies catch diagnostic failures and fail open.
- Dependencies are causal IDs, not structural guesses: bootstrap depends on Discovery; gather depends on bootstrap only when that task actually exists.

## Tests

`MinecraftBootstrapTraceTransformerTest` verifies:

- begin hook -> original exact bootstrap body -> end hook ordering;
- every normal `RETURN` is closed;
- missing-return, ambiguous-method and wrong-class inputs are rejected without injection.

#202's existing `FmlLoadingTraceTransformerTest` continues to reject direct instrumentation of bootstrap-loaded `net.neoforged.fml.ModLoader`.

## Hosted validation

Validated runtime-code commit: `57791d4ab40e877f7b72abb802db1d7cdd4aa993`.

- Build/package run `34280786312`: **success**.
- Normal Startup Benchmark run `34280786386`: **success** to `main_menu` with trace default/off.
- Hosted exact-pack profile run `34280786382`: **success** to `main_menu`.
- Exact-pack artifact `10077639319`, digest `sha256:12693595b96bdc8ff3e7706b0faad9b6d7857dfeed434a362b7a8ff0e04059cc`.

Exact-pack result: `main_menu_ms=90971`, `mod_entrypoint_ms=30691`, `reload_to_fancymenu_finish_ms=41775`, `bootoptim_mixin_errors=0`, blocks atlas `8192x8192x2`. These are compatibility/profile observations, not an A/B and not a speedup claim.

Structured JSONL integrity:

- exactly one `bootoptim.boottrace` v1 header and one summary;
- `measurement_origin=hosted_exact_pack`, `endpoint=main_menu`, `mode=profile`;
- 4 balanced `task_begin` / 4 `task_end` pairs;
- `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`, `error=0`;
- `minecraft_bootstrap` task id 3 has `dependency_ids:[2]` where id 2 is `dependency_discovery`;
- `fml_gather_and_initialize_mods` task id 4 has `dependency_ids:[3]`.

Monotonic intervals from that run:

| Boundary | Type | Wall |
| --- | --- | ---: |
| `dependency_discovery` | inclusive task wall | 7331.573 ms |
| Discovery end -> bootstrap begin | **untraced temporal gap** | 10939.394 ms |
| `minecraft_bootstrap` | inclusive task wall | 4234.562 ms |
| bootstrap end -> gather begin | **untraced temporal gap** | 3191.482 ms |
| `fml_gather_and_initialize_mods` | inclusive task wall | 5472.826 ms |
| Discovery end -> gather begin | temporal gap total | 18365.437 ms |

The two residual gaps plus bootstrap reconstruct the temporal Discovery->gather interval, but they are deliberately not named as tasks and are not treated as savings. The first residual contains FML/ModLauncher post-discovery and game-layer launch work before `Bootstrap.bootStrap`; the second contains validation/transition/client-loading preamble before the gather call. A future diagnostic must find another semantically exact observable edge for either residual rather than wrapping a generic classloader scope.

## Disposition

Keep PR #203 as a separate diagnostic candidate only; do not merge it automatically as production. It establishes that approximately 4.235 s of one validated hosted gap is Minecraft bootstrap wall and narrows the still-uncovered portions to approximately 10.939 s before bootstrap plus 3.191 s after bootstrap in that run. These numbers are profile wall intervals, not TTMM savings or task-sum.

No physical-laptop run was requested or used.

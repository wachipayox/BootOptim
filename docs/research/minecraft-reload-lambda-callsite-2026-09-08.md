# Minecraft reload lambda callsite mapping — 2026-09-08

Status: diagnostic result, not an optimization.

Base integration: `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
PR: #189. Probe property: `-Dboot_optim.profileMinecraftReloadLambdaIdentity=true`.
Exact-pack fixture SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

## Boundary

PR #184 measured ordered, monotonic exclusive apply slots after the preparation gate. It reported index 25 = 3590.560 ms and index 26 = 852.055 ms, both anonymously named `net.minecraft.client.Minecraft$$Lambda/...`. Those durations are retained evidence from #184; this probe does not time listener bodies. PR #183 remains the real-class replay boundary for ordinary BlockModel parse/parent/material work and that work is not attributed to these listeners.

## Probe

`ReloadableResourceManagerLambdaIdentityMixin` observes only two low-frequency points:

1. `registerReloadListener`: when and only when the listener stable class starts with `net.minecraft.client.Minecraft$$Lambda`, capture one registration stack, object identity, source frame and non-static captured-field *types*.
2. `createReload`: enumerate NeoForge's final sorted `listeners` list and correlate the exact same object identity with its final index.

No listener is wrapped or replaced. No future, executor, barrier, scheduling, GL/render-thread operation, callback or resource order is changed. Disabled, both hooks short-circuit. Enabled, the only stack capture occurs once per matching registration.

## Hosted exact-pack result — VM 1

Actions: `34213276434`, artifact `10050805614` (`exact-pack-result-smoke-1`, artifact ZIP digest `sha256:52cb6132c357d3328c4eee8c73658bd1e685788e64eaf8d07d7e9fa84ac2334b`). Build `34213276588` and generic startup smoke `34213276428` both passed. Exact-pack smoke reached the menu (`main_menu_ms=69225`, process-origin smoke metric) with `bootoptim_mixin_errors=0`.

The exact final listener list had 72 entries and exactly two `Minecraft$$Lambda` objects. Both registration identities matched the final ordered objects:

- index 25: identity `812613429`; registration callsite `net.minecraft.client.Minecraft.handler$fca000$moreculling$onBlockRenderManagerInitialized(Minecraft.java:30046)`; empty captured-field set.
- index 26: identity `534904312`; registration callsite `net.minecraft.client.Minecraft.handler$fca000$moreculling$onBlockRenderManagerInitialized(Minecraft.java:30050)`; empty captured-field set.

Probe self-overhead for the only two stack/field-type captures was 657.218 us + 44.405 us = **701.623 us total**. This is instrumentation self-time, not startup A/B evidence.

The pack log identifies `More Culling 1.0.8 (moreculling)`. Therefore these are not vanilla Minecraft-owned listener bodies: More Culling injects the handler into `Minecraft` and the JVM names the generated listener classes `Minecraft$$Lambda`.

## Concrete bodies and dependencies

The matching More Culling source shape is visible in upstream commit `9f29c0e8bb97aa3aa957d1fc910ac9b1db792eb4`, `fabric/src/main/java/ca/fxco/moreculling/mixin/Minecraft_registerReloadListenersMixin.java`: the handler registers exactly two consecutive `ResourceManagerReloadListener` lambdas in this order.

### Index 25

Body:

`Block.BLOCK_STATE_REGISTRY.forEach(state -> ((StateCullingShapeCache) state).moreculling$initCustomCullingShape())`

Dependencies/touches:

- iterates the global block-state registry;
- invokes More Culling's `StateCullingShapeCache` extension on every state;
- mutates More Culling-owned per-state culling-shape cache state;
- ignores the `ResourceManager` lambda argument;
- no direct GL call and no `ModelManager` parse/parents/material work is present in this listener body.

Classification: **(b) mutable mod callback/cache work**, not (c). It is a More Culling extension callback over modded block states and publishes mutable culling cache state. No prepare/commit candidate is authorized by this PR.

### Index 26

Body:

`((BlockModelShaperAccessor) blockRenderManager.getBlockModelShaper()).getModels().forEach((state, model) -> { if (!state.canOcclude()) ((BakedOpacity) model).moreculling$resetTranslucencyCache(state); })`

Dependencies/touches:

- reads More Culling's `blockRenderManager` and `BlockModelShaper` model map, therefore it is downstream of baked-model publication;
- iterates baked block-state/model pairs;
- mutates More Culling `BakedOpacity` translucency caches on model objects for non-occluding states;
- ignores the `ResourceManager` argument;
- no direct GL call is in the body, but it is semantically coupled to published baked models.

Classification: **(b) mutable mod callback/cache work**, not (c). Moving it before baked-model publication or parallelizing cache mutation is not justified by this mapping.

Upstream issue FxMorin/MoreCulling#414 independently records failures inside this same second handler/lambda when model publication is inconsistent, reinforcing that ordering against baked models is semantically significant.

## Decision / next gate

The anonymity is resolved: #184 indices 25/26 are More Culling cache-maintenance listeners injected into `Minecraft`, not unexplained vanilla work and not BlockModel parse/parents/material work. Neither qualifies as category (c) on current evidence. The next candidate gate, if pursued separately, is More Culling-specific: prove whether either cache can be computed into detached immutable data from an already-published model/state snapshot and committed on the original apply thread without invoking mod-overridable behavior or exposing partially updated caches. Until that proof exists, do not change scheduling.

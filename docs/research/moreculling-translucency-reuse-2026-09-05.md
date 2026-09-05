# MoreCulling reload-local translucency reuse — 2026-09-05

Status: **ACTIVE CANDIDATE / HOSTED VALIDATION PENDING**

Diagnostic precursor: PR #108, branch `codex/diagnostic-moreculling-startup`.

Candidate branch: `codex/moreculling-translucency-cache`, forked from `agent/integration-current` at `2fd0f62748b7a65aca24bcdbad43aba5d4b469d9`.

This candidate is deliberately narrower than MoreCulling's model caches. It does not change model hooks, culling shapes, resource order, callbacks, sprites, mipmaps, animation, rendering, or gameplay behavior. It only reuses the exact boolean returned by MoreCulling 1.0.8's private `SpriteUtils.doesHaveTranslucency(NativeImage, List, int, int, int, int)` when the same `NativeImage` object and exact bounds recur during one resource-reload generation and `orMatch == null`.

## Diagnostic premise

Final exact-pack diagnostic run `33975337605`, artifact `9972188150`, preserved all ten external ZIPs in exact order, one reload, atlas `8192x8192x2`, and zero BootOptim Mixin errors.

Measured MoreCulling work:

- shape cache: `313,683 / 313,683` states, `2,942.407 ms` wall, `2,668.421 ms` current-thread CPU;
- SpriteUtils translucency scan: `602,308` calls, `598.828 ms` direct task-sum;
- exact repeated `(NativeImage identity, bounds)` calls: `450,293`;
- direct wall in those repeated calls: `395.731 ms`;
- result mismatches: `0`;
- tracked unique keys: `4,096`, then bounded tracker saturation `147,919` calls / `186.347 ms` untracked.

The shape-cache CPU is attribution, not the target of this candidate. Model-level translucency coverage was only `81.222%`, so this candidate does not cache or replace model hooks.

## Exact source and semantic proof

Exact pack identity work in PR #108 established that the renamed pack JAR is MoreCulling `1.0.8` NeoForge. Upstream source is `FxMorin/MoreCulling` tag `v1.0.8`, tag commit `6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682`, release target `1.21.1-multiloader`.

For `orMatch == null`, `SpriteUtils.doesHaveTranslucency` has no callback or hidden state. It checks `image.format().hasAlpha()` and scans the supplied integer rectangle until `image.getLuminanceOrAlpha(x, y) != -1`; that exact comparison is the translucency threshold. The result therefore depends only on the addressed bytes of that `NativeImage`, its format, and the exact bounds.

MoreCulling's `TextureAtlasSprite_opacityMixin` obtains the image through `SpriteContentsAccessor.originalImage`; it explicitly calls this the unmipmapped image. Minecraft 1.21.1 exposes `SpriteContents.originalImage` as `private final`. Mipmap storage is a separate `byMipLevel` array. Animated interpolation owns a separate `NativeImage[] activeFrame`; frame ticking/upload changes GPU upload content and/or the interpolation buffer, not the `originalImage` reference used by MoreCulling's scan.

`NativeImage` itself is mutable, so object identity alone is not safe for arbitrary lifetime. Safety comes from the cache lifetime: it is created/cleared at each `ReloadableResourceManager.createReload`, is usable only while that `ReloadInstance` is incomplete, and is cleared again from `ReloadInstance.done()`. Resource reload apply work is the lifecycle in which MoreCulling rebuilds these caches; the candidate retains no image/result after completion and is inactive during gameplay. A late completion from an older generation cannot clear a newer generation because completion carries a generation token.

No reuse is allowed for non-null `orMatch`, ResourceLocation, sprite name, model identity, quad count, mipmap, or cross-reload identity.

## Candidate mechanism

JVM property, default off:

`-Dboot_optim.moreCullingTranslucencyCache=true`

The kill switch is therefore the default/explicit `false` value.

The implementation uses a fixed-capacity `4,096`-entry open-addressed cache keyed by object identity plus all four integer bounds. The capacity intentionally matches the diagnostic tracker that already captured `395.731 ms` of exact repeated scan wall; saturation fails open to the stock scan rather than evicting or broadening identity.

On cache miss MoreCulling executes unchanged stock code and the returned boolean is stored. On hit the exact stored boolean is returned. Non-null `orMatch`, inactive reload scope, saturation, absent MoreCulling hooks, or an internal cache runtime failure all continue through stock behavior. Runtime failures clear and disable reuse for the current reload.

At reload completion one aggregate mechanism line reports hits, misses, stores, saturation, layered bypasses, fail-open state, and reload failure. There is no per-pixel, per-quad, or per-call logging.

## Tests

`gradlew build` runs the no-dependency cache regression harness through `check`. It verifies:

- exact identity+bounds hit and miss behavior;
- cached `false` is distinguishable from miss;
- clear/invalidation removes all results;
- mutating the same logical image between reload generations cannot reuse the old result after invalidation;
- the configured capacity does not evict/reinterpret existing entries.

The mutation test models cross-generation mutation. It does not assert that arbitrary mutation is safe inside an active generation; the source/lifecycle proof above is the precondition that excludes such mutation while MoreCulling's reload listener is consuming the atlas data.

## Gate

Required sequence:

1. Build + normal Startup CI.
2. Exact-pack smoke with the candidate explicitly enabled; preserve the #103 ten-pack/order/fallback gate, atlas contract, main menu, and zero Mixin errors.
3. Only after a clean smoke, hosted same-branch 3x3 candidate/control A/B.
4. Judge TTMM and reload-to-FancyMenu together with cache hit/saturation markers. The `395.731 ms` repeat wall is a direct diagnostic ceiling, not promised TTMM savings.
5. Do not request laptop unless hosted end-to-end is positive and the remaining question is plausibly hardware-sensitive.

No performance conclusion is recorded until the A/B gate completes.

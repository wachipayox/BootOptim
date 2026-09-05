# MoreCulling reload-local translucency reuse — 2026-09-05

Status: **REJECTED / CLOSE WITHOUT MERGE**

Diagnostic precursor: PR #108, branch `codex/diagnostic-moreculling-startup`.

Candidate PR: #119, branch `codex/moreculling-translucency-cache`, originally forked from `agent/integration-current` at `2fd0f62748b7a65aca24bcdbad43aba5d4b469d9` and validated against the current PR base.

This experiment tested a deliberately narrow cache around MoreCulling 1.0.8's private `SpriteUtils.doesHaveTranslucency(NativeImage, List, int, int, int, int)`. It never changed model hooks, culling shapes, resource order, callbacks, sprites, mipmaps, animation, rendering, or gameplay behavior. Reuse was allowed only when `orMatch == null`, the exact `NativeImage` object identity matched, all four integer bounds matched, and the call belonged to one resource-reload generation.

The semantic premise survived review and the candidate passed build/startup/smoke gates. The implementation is nevertheless **rejected** because its global synchronization strategy failed the hosted end-to-end gate badly. No code from PR #119 should be promoted.

## Diagnostic premise

Final exact-pack diagnostic run `33975337605`, artifact `9972188150`, preserved all ten external ZIPs in exact order, one reload, atlas `8192x8192x2`, and zero BootOptim Mixin errors.

Measured MoreCulling work:

- shape cache: `313,683 / 313,683` states, `2,942.407 ms` wall, `2,668.421 ms` current-thread CPU;
- SpriteUtils translucency scan: `602,308` calls, `598.828 ms` direct task-sum;
- exact repeated `(NativeImage identity, bounds)` calls: `450,293`;
- direct wall in those repeated calls: `395.731 ms`;
- result mismatches: `0`;
- tracked unique keys: `4,096`, then bounded tracker saturation `147,919` calls / `186.347 ms` untracked.

The shape-cache CPU was attribution only and was never part of this candidate. Model-level translucency coverage was only `81.222%`, so model hooks were intentionally left untouched.

The important economic constraint is the `395.731 ms` direct repeated-scan wall: it is a ceiling on work this specific cache could avoid in the measured diagnostic, not a TTMM promise. That sub-second ceiling does not justify an implementation that adds global synchronization to a path called hundreds of thousands of times.

## Exact source and semantic proof

Exact pack identity work in PR #108 established that the renamed pack JAR is MoreCulling `1.0.8` NeoForge. Upstream source is `FxMorin/MoreCulling` tag `v1.0.8`, tag commit `6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682`, release target `1.21.1-multiloader`.

For `orMatch == null`, `SpriteUtils.doesHaveTranslucency` has no callback or hidden state. It checks `image.format().hasAlpha()` and scans the supplied integer rectangle until `image.getLuminanceOrAlpha(x, y) != -1`; that comparison is the translucency threshold. The result depends only on the addressed bytes of that `NativeImage`, its format, and the exact bounds.

MoreCulling's `TextureAtlasSprite_opacityMixin` obtains the image through `SpriteContentsAccessor.originalImage`, explicitly treating it as the unmipmapped image. Minecraft 1.21.1 keeps `SpriteContents.originalImage` as `private final`. Mipmap storage is separate in `byMipLevel`; mip generation retains level zero and creates distinct images for additional levels. Animated interpolation owns a separate `NativeImage[] activeFrame`, so frame ticking/upload does not replace the `originalImage` reference used by the scan.

`NativeImage` is mutable in general, so object identity alone is not safe for arbitrary lifetime. The candidate therefore scoped reuse to one `ReloadableResourceManager` generation, cleared at reload start and again from `ReloadInstance.done()`, with a generation token preventing an older completion from invalidating a newer generation. MoreCulling itself already memoizes sprite translucency on `BakedQuad` until explicit reset, so the experiment did not introduce a broader lifetime assumption than the mod already uses during a generation.

No reuse was allowed for non-null `orMatch`, ResourceLocation, sprite name, model identity, quad count, mipmap, or cross-reload identity.

This semantic result remains useful: a future implementation may reuse exact image+bounds results only if it preserves the same equivalence and invalidation constraints. Semantic safety alone did not make this implementation economically acceptable.

## Candidate mechanism

JVM property, default off:

`-Dboot_optim.moreCullingTranslucencyCache=true`

The explicit/default `false` value is the kill switch.

The candidate used a fixed-capacity `4,096`-entry open-addressed cache keyed by object identity plus all four integer bounds. Saturation executed the stock scan rather than evicting or broadening keys. Non-null `orMatch`, inactive reload scope, absent MoreCulling hooks, saturation, or an internal runtime failure all fell through to stock behavior.

The implementation flaw is synchronization: lookup/store operations are guarded by `synchronized (LOCK)`. Because the measured path executes roughly six hundred thousand calls, putting one global JVM monitor around every cache access serializes a hot startup path. The primary A/B regression is attributed to this global-lock design. Regardless of the exact contention distribution on individual hosted runners, this synchronization cost is far larger than the diagnostic work ceiling that motivated the cache.

## Tests and pre-A/B gates

`gradlew build` runs the no-dependency cache regression harness through `check`. It verifies exact identity+bounds hits/misses, cached `false` versus miss, invalidation, mutation across reload generations, and fixed capacity without reinterpretation/eviction.

Candidate code head `8d84c239752b859667901d62f72fedea3786f708` passed:

- Build `33981523735`: PASS, including the cache harness and packaged bootstrap validation.
- Startup `33981523739`: PASS with the feature default-off.
- Exact-pack smoke `33981523741`, artifact `9973949612`: PASS with the candidate enabled.
- smoke TTMM `95,122 ms`; reload→FancyMenu `44,383 ms` — sanity values only, not performance evidence.
- exact resource selection valid, all ten external ZIPs in expected order, one effective reload.
- blocks atlas `8192x8192x2`; BootOptim Mixin errors `0`.
- live cache marker: `hits=451707 misses=150713 stores=4096 saturated=146617 layered_bypass=0 entries=4096 failed_open=false reload_failure=none`.

The smoke proved that the cache was active and semantically compatible with the hosted fixture. It did not predict end-to-end benefit.

## Primary hosted A/B — rejected

Primary same-branch exact-pack A/B 3x3: run `33981861574`, summary artifact `9974088582`.

| Metric | Candidate median | Control median | Candidate - control |
| --- | ---: | ---: | ---: |
| TTMM | `91.878 s` | `76.873 s` | **`+15.005 s (+19.52%)`** |
| mod entrypoint | `29.661 s` | `25.061 s` | `+4.600 s (+18.36%)` |
| post-mod | `62.217 s` | `51.812 s` | `+10.405 s (+20.08%)` |
| MCEF | `1.984 s` | `1.380 s` | `+0.604 s (+43.77%)` |
| reload→FancyMenu | `41.488 s` | `35.846 s` | **`+5.642 s (+15.74%)`** |
| panorama | `3.9138 s` | `3.7076 s` | `+206.2 ms (+5.56%)` |

All six runs preserved atlas `8192x8192x2`, the exact-pack resource contract, and zero BootOptim Mixin errors. The candidate therefore failed on performance, not semantic fixture integrity.

The regression dwarfs the diagnostic `395.731 ms` direct repeated-scan ceiling. The project decision is that `synchronized (LOCK)` on every lookup/store is an unacceptable hot-path serialization mechanism for this workload. A high hit count does not offset the synchronization tax.

## Duplicate A/B

A later unintended duplicate A/B, run `33981907944`, summary artifact `9974102121`, also completed with six valid runs. It produced the opposite median sign:

- candidate TTMM `91.738 s` vs control `94.512 s` (`-2.774 s`, `-2.94%`);
- candidate reload→FancyMenu `42.711 s` vs control `44.016 s` (`-1.305 s`, `-2.96%`);
- candidate panorama `3.9092 s` vs control `4.0766 s` (`-167.5 ms`, `-4.11%`);
- atlas remained `8192x8192x2` and Mixin errors remained zero.

This duplicate does **not** rehabilitate the globally locked candidate and is not a reason to request another A/B. It demonstrates that hosted full-pack variance is much larger than the sub-second diagnostic ceiling of the target work. The primary decision run already exposed an implementation-level failure mode with catastrophic downside, while the duplicate shows the end-to-end signal is not stable enough to justify carrying that synchronization risk.

## Decision

**REJECTED / CLOSE WITHOUT MERGE.**

- Do not promote PR #119.
- Do not request a laptop for this design.
- Do not repeat another A/B with the same global-lock mechanism.
- Do not generalize the result into model-hook caching; that path was never covered sufficiently and was not part of this experiment.
- Keep the semantic proof only as a constraint for a future materially different implementation.

### Reopening criterion

Reopen this direction only if a new design removes the global lock from the hot lookup/store path while preserving all of the following:

1. exact `NativeImage` identity plus exact bounds equivalence;
2. no reuse for non-null `orMatch`;
3. strict per-reload invalidation with no retained image/result across reloads;
4. fail-open stock behavior on unsupported/failure paths;
5. explicit tests for hit/miss, invalidation, mutation boundaries, and concurrency/lifetime assumptions;
6. a new exact-pack hosted end-to-end A/B that shows a stable TTMM/reload→FancyMenu benefit large enough to justify the mechanism.

A high repeat count alone is not a reopening premise.
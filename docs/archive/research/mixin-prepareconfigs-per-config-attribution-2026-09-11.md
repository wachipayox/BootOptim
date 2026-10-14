# Mixin `prepareConfigs` per-config attribution — 2026-09-11

Status: **PROFILED / NO-GO for optimization at the current boundary**

PR: #256. Authority base: `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Question

Profiles in #230/#232 put roughly 2.5–3.0 s of serial startup wall inside `MixinProcessor.prepareConfigs`. Generic parallel preparation is already rejected because config/plugin/target/listener state is order-sensitive. This experiment asks only which real config/subphase owns the wall and whether it exposes a direct version-pinned fix, pack configuration change, or demonstrably repeated pure work.

## Method and contract

The branch reuses the source-pinned Fabric Mixin fork from #232 (`023e39334850e839c283be413257bf459f40a5d6`) and the ModLauncher causal probe from #229. A second fail-closed patch surrounds the existing stock calls, without moving them:

- each `MixinConfig.prepare(extensions)` (`config_prepare_one`), with config name and declared mixin count;
- each `IMixinConfigPlugin.acceptTargets` (`plugin_accept_targets_one`), with config/plugin and target cardinalities;
- each `MixinConfig.postInitialise` (`post_initialise_one`).

No class initialization is forced, no bytecode/target object is cached, no scheduler/executor is changed, and no ModLauncher/SecureJarHandler cache is reopened. Parent scopes remain inclusive; child observations are never added to them as extra wall.

The first Agent 124 run reached the menu and passed the exact-pack runtime gate. Its resource-selection check was `valid=true`, observed all 14/14 resources in the expected order, `reload_count=1`, and `bootoptim_mixin_errors=0`. `main_menu_ms=77627`. This is a smoke/attribution run, not an A/B or savings result.

The raw run contained three stderr marker lines split by concurrent log output. All 330 `config_prepare_one` observations were recoverable unambiguously from adjacent fragments; the diagnostic emission was then hardened in commit `5e567785f5fa90cd160337328a0c285ca03edea5` to format the full line first and emit it with one `println` call. This is diagnostic robustness only and does not change Mixin control flow.

## Exact-pack attribution

Single-run monotonic wall from the valid hosted exact-pack smoke:

| stock phase | wall |
| --- | ---: |
| `prepareConfigs` inclusive | 2039.596 ms |
| listener registration | 0.639 ms |
| config `prepare` loop | 1679.918 ms |
| plugin `acceptTargets` loop | 38.171 ms |
| `postInitialise` loop | 320.347 ms |
| config commit | 0.093 ms |

The 330 child `config.prepare` calls account for 1660.274 ms. The ~19.64 ms parent-minus-children remainder includes loop/bookkeeping and the diagnostic print boundaries, so it is not classified as stock optimization wall.

Top `config.prepare` children:

| config | declared | wall | share of observed child prepare |
| --- | ---: | ---: | ---: |
| `lithium.mixins.json` | 236 | 138.601 ms | 8.35% |
| `modernfix-modernfix.mixins.json` | 153 | 122.715 ms | 7.39% |
| `mixins.iris.json` | 96 | 69.448 ms | 4.18% |
| `exposure-common.mixins.json` | 36 | 64.833 ms | 3.90% |
| `mixins.litematica.json` | 35 | 56.008 ms | 3.37% |
| `sable-neoforge.mixins.json` | 158 | 51.611 ms | 3.11% |
| `c2me-fixes-worldgen-threading-issues.mixins.json` | 36 | 50.734 ms | 3.06% |
| `sable.mixins.json` | 215 | 43.532 ms | 2.62% |
| `we_companion.mixins.json` | 38 | 39.712 ms | 2.39% |
| `connectivity.mixins.json` | 28 | 37.674 ms | 2.27% |

No single config owns the phase: the largest is only 8.35%; top 10 are 40.65%, top 20 54.52%, top 50 74.70%. Declared mixin count and observed `prepare` wall have Pearson correlation about 0.837 in this run (3713 declared mixins total), consistent with broadly distributed per-mixin class/metadata/target work rather than one pathological plugin.

`postInitialise` is similarly distributed. The largest children were `neoforge-asyncparticles.target-modifier.mixins.json` 22.028 ms, Lithium 16.712 ms, Sable 14.399 ms, ModernFix 13.524 ms, Iris 12.172 ms and FancyMenu 11.179 ms.

The 131 actual `plugin.acceptTargets` callback bodies total only ~0.689 ms. Therefore almost all of the enclosing 38.171 ms plugin-loop wall is outside the callback bodies, chiefly construction of each plugin's `otherTargets` set and loop/bookkeeping. That is real repeated work, but its entire ceiling is only ~37.5 ms in this smoke and the stock code constructs a fresh `HashSet` in pending-config order. Replacing it with a shared/precomputed representation has an observable Set-iteration/failure surface and is not justified at this leverage.

## Critical-path correlation

The first `net.minecraft.server.Bootstrap` request remains the same causal request proven by #228/#229: Mixin transformed-byte acquisition on the main thread; the second is later normal classloading on the worker with the same loader/TCCL.

In this run request 1 occurs inside `modernfix-modernfix.mixins.json` preparation:

- ModernFix config prepare start (derived from its measured child): `283997405550 ns`;
- Bootstrap transform begin: `284073924579 ns`;
- request 1 end: `284075386422 ns`;
- ModernFix config prepare end: `284120120978 ns`.

Thus the observed Bootstrap transformation itself is about 1.46 ms inside a 122.72 ms ModernFix config prepare; it is a causal landmark, not the explanation for the config wall. From request-1 end to `prepareConfigs` exit is ~1.807 s, and request-1 end to request-2 transform begin remains ~4.386 s. The attribution is therefore on the serial critical path without treating inclusive scopes as additive.

## Source-level interpretation

Pinned Mixin source makes the broad `prepare` scope unsuitable for an external cache or worker split:

- `MixinConfig.prepareMixins` checks the global mixin list, constructs `MixinInfo`, mutates pending/global lists and records targets;
- `MixinInfo` construction obtains/parses mixin class bytes, creates state/ClassInfo/subtype information and reads declared targets;
- target reading can invoke `IMixinConfigPlugin.shouldApplyMixin(targetName, mixinClassName)` while targets are being interpreted;
- `postInitialise` may obtain plugin-provided mixins, prepare them, validate `MixinInfo`, notify listeners and remove invalid mappings.

That means the measured per-config wall is not a source-level proof of a pure JSON/ASM parse island. Class acquisition/transformation, plugin decisions, global duplicate/order state, target registration/ClassInfo state, listener-visible initialization, validation and publication are interleaved.

The exact pack used Lithium `0.15.3+mc1.21.1`, ModernFix `5.27.14+mc1.21.1`, Iris `1.8.14-beta.1+mc1.21.1`, Exposure `1.9.18` and Sable `2.0.3`. ModernFix logged five option overrides, including `mixin.perf.dynamic_dfu=false` from Litematica; Lithium logged four Sable-related rule overrides. A pack setting that disables/removes these mixins would change gameplay/compatibility, and plugin-level disabling does not establish that Mixin can skip the class-byte/target work before the decision. No safe pack-configuration win is demonstrated.

## Decision

**No-go** for a BootOptim production optimization at `prepareConfigs`, whole-config `prepare`, plugin-target union, or current pack configuration.

There is no dominant config/plugin whose direct patch plausibly recovers the multi-second residual. The largest config is <140 ms in this hosted run, plugin callback bodies are sub-millisecond in aggregate, and the only obvious repeated loop work has a ~37.5 ms single-run ceiling with observable Set semantics. The remaining large wall is broad per-mixin preparation coupled to mutable Mixin state.

No startup-savings claim is made from this smoke.

## Reopening criterion

Reopen only with a materially new premise, for example:

1. at least two valid exact-pack attribution runs show the same config or source subphase materially dominating (not merely a large mixin count);
2. a source-pinned Mixin/mod change exposes an immutable read/preparse result whose production is proven independent of class acquisition/transformation, `shouldApplyMixin`/plugin callbacks, global mixin ordering, `ClassInfo`/target registration, listeners/extensions, validation and error order; or
3. a newer exact mod version removes demonstrably redundant preparation while preserving the exact pack's behavior, after a same-contract smoke and then a causal A/B.

Do not reopen generic `prepareConfigs` parallelism, live bytecode/target caching, or ModLauncher/SecureJarHandler caching from this result.

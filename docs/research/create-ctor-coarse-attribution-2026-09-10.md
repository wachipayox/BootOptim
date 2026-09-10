# Create 6.0.10 constructor coarse attribution — 2026-09-10

Status: **DIAGNOSTIC COMPLETE / NO OPTIMIZATION**

## Scope and authority

Agent 108 starts from `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b` and follows PR #238's validated FML critical-chain method. This branch is profile-only. It does not rewrite the FML scheduler, registration order, event-bus callbacks, mod state, threads, failures, or gameplay behavior, and it requests no laptop run or candidate/control A/B.

PR #238 reproduced the hosted dependency lineage `colorwheel -> flywheel -> ponder -> create -> ratatouille -> ratatouille_fried_delights`. On its current-head repeat, Create measured 1036.546 ms as an outer construction node, including 826.305 ms constructor-exclusive, 210.219 ms automatic-subscriber injection, and 0.016 ms `FMLConstructModEvent`. Those values are attribution, not removable savings.

## Exact source boundary

The exact-pack Create runtime is 6.0.10, corresponding to public Create commit `ac0c444d9828da3453ae8cc65338e8de063286fb`. `Create.onCtor(IEventBus, ModContainer)` executes a stock serial sequence of Registrate listener setup, registry families, config registration, package/schematic defaults, bogey initialization, compatibility setup, event-bus listener registration, and optional compatibility initialization.

The source explicitly marks `BogeySizes.init()` + `AllBogeyStyles.init()` as containing registrations that are not thread-safe. It separately marks the final Curios compatibility initialization as not thread-safe. This diagnostic never moves, parallelizes, separates, or invokes those regions independently.

## Probe design

The PR #238 profile-only Java agent is retained for the FML outer DAG and extended only with observational advice on exact Create 6.0.10 boundaries. Create identity/version is checked from the already-supplied `ModContainer` metadata at normal `onCtor` entry; the expected runtime is `create@6.0.10`, and the source pin above is emitted in the trace header. A mismatch records `create_profile_disabled` and the analyzer fails closed.

No call is substituted. Coarse phase endpoints are method exits that already occur in stock source order. Taking consecutive timestamps yields a contiguous serial partition that naturally includes class initialization and intervening bytecode in the owning source slice:

1. `setup_and_registrate`: `onCtor` entry through `CreateRegistrate.registerEventListeners` return;
2. `registration_families`: through `AllMountedStorageTypes.register` return;
3. `config_registration`: through public `AllConfigs.register(ModLoadingContext, ModContainer)` return;
4. `package_and_schematic_defaults`: through `AllSchematicStateFilters.registerDefaults` return;
5. `bogey_non_thread_safe`: through `AllBogeyStyles.init` return, intentionally keeping the upstream non-thread-safe bogey region intact;
6. `compat_and_milk`: through `NeoForgeMod.enableMilkFluid` return;
7. `listener_and_optional_compat_tail`: through stock `onCtor` return, intentionally keeping listener order and final optional compatibility callbacks intact.

The analyzer requires exactly those six intermediate endpoints, in that order, on the same immutable FML worker thread, nested inside the single `create` FML construction interval. It also requires the PR #238 dependency subchain `colorwheel -> flywheel -> ponder -> create -> ratatouille`. The seven phase walls must tile `Create.onCtor` exactly; they are never summed with overlapping FML nodes.

## Semantics and interpretation

The boundaries are observer calls after existing method returns. They do not call registration methods, wrap an event bus, move a callback, replace a future/executor, or modify publication. Trace events remain in memory until shutdown as in #238.

A large phase remains only an investigation target. In particular, registry-family and configuration phases mutate registries/builders/listeners and can trigger class initialization; the bogey and final optional-compat regions have explicit upstream thread-safety warnings. No phase is classified pure merely from elapsed time. A future prepare -> barrier -> commit proposal is allowed only if source and runtime evidence identify a material product that is independent of registration order, class initialization, callbacks, mutable registries/builders, and failure/publication order.

## Hosted gate

Accepted evidence is workflow run `34499637149`, job `102946861292`, artifact `10161427845`, on instrumentation head `871e8296c191040b2b2216bbbc3b00f7312d9131`.

The exact-pack smoke used pinned fixture `exact-pack-2026-09-02-v1`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`, Oracle JDK 25.0.4, `-XX:ActiveProcessorCount=4`, Xvfb/llvmpipe, and pinned JCEF commit `a78e832f9f13c2c688caea3d04d8b84fcd238d94`.

All required gates passed: build/tests and Python compile; main menu reached; `bootoptim_mixin_errors=0`; resource selection exactly 14/14 in the reference order with no issues and `reload_count=1`; FML header exactly `fml_loader@4.0.43`; Create header exactly `create@6.0.10` with package version 6.0.10 and source pin `ac0c444d9828da3453ae8cc65338e8de063286fb`; 250 complete FML nodes and dependency records; 4 workers; required dependency subchain present; all six Create boundaries observed exactly once, in order, on immutable worker tid 80; phase tiling error was effectively zero (`1.14e-13 ms`).

The smoke reported `main_menu_ms=91698` and `mod_entrypoint_ms=31705`. These are profile-only values, not candidate/control A/B and not savings.

A first bring-up run `34499054830` reached menu and kept resources valid but was excluded from timing conclusions because the initial `AllConfigs.register` matcher also matched its three private two-argument helper invocations, yielding nine instead of six coarse boundaries. The matcher was narrowed to the public overload and the accepted run above re-executed the full exact-pack profile.

## Result

FML construction gate wall was **5496.347 ms** with only **2.921 ms** post-node gate residual. The dependency lineage remained:

`colorwheel -> flywheel -> ponder -> create -> ratatouille -> ratatouille_fried_delights`

Create remained both dependency-chain and observed execution-chain critical: outer node **1037.884 ms**, constructor-exclusive **846.260 ms**, automatic subscriber **191.599 ms**, construct event **0.019 ms**. Exact stock `Create.onCtor` itself measured **778.614 ms**.

| stock serial `Create.onCtor` phase | wall ms | share of `onCtor` |
| --- | ---: | ---: |
| `setup_and_registrate` | 0.645 | 0.08% |
| `registration_families` | **722.248** | **92.76%** |
| `config_registration` | 24.702 | 3.17% |
| `package_and_schematic_defaults` | 4.348 | 0.56% |
| `bogey_non_thread_safe` | 12.709 | 1.63% |
| `compat_and_milk` | 8.968 | 1.15% |
| `listener_and_optional_compat_tail` | 4.995 | 0.64% |

The coarse DAG inside the critical Create node is therefore:

`setup_and_registrate -> registration_families -> config_registration -> package_and_schematic_defaults -> bogey_non_thread_safe -> compat_and_milk -> listener_and_optional_compat_tail`

The dominant finding is narrow: **722.248 ms / 92.76% of `Create.onCtor` is before the return of `AllMountedStorageTypes.register`, in Create's stock registration-family sequence.** The remaining six coarse slices sum to only about 56.367 ms. This is causal attribution inside one serial method, not a claim that 722 ms is removable.

## Safe-frontier decision

**No pure prepare -> barrier -> commit frontier is demonstrated by this profile, so no optimization is justified.**

The dominant `registration_families` slice executes registrations and class initialization across sound events, creative tabs, armor materials, display sources/targets, blocks, items, fluids, palettes, menus, entities, block entities, recipes, particles, structure processors, entity serializers, packets, worldgen, ingredients, attachments, data components, map decorations, and mounted storage. These operations mutate Registrate/deferred-register state and attach registration/callback behavior; their source order and class-initialization/failure order are observable. Treating the whole 722 ms region as pure or off-thread work would violate the contract.

The smaller slices are also not safe candidates from current evidence: `AllConfigs.register` constructs/publishes config objects, calls `ModContainer.registerConfig`, and registers stress providers; package/schematic defaults mutate registries; the bogey region is explicitly marked upstream as containing non-thread-safe registrations; milk/compat touches external/global compatibility state; and the final tail adds event listeners then executes optional compatibility initialization including an upstream non-thread-safe Curios path.

Therefore the next justified work, if pursued, is **diagnostic subdivision only inside `registration_families`**, using exact source-defined family endpoints while preserving the existing call order and leaving any upstream-marked non-thread-safe region intact. A later optimization proposal must identify a concrete material sub-operation whose prepare result is demonstrably independent of JVM class initialization, mutable Registrate/registry state, callbacks, publication, and failure ordering. Do not reopen the generic FML scheduler and do not parallelize Create registration from this evidence.

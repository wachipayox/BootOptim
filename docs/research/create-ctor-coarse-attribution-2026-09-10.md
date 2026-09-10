# Create 6.0.10 constructor coarse attribution — 2026-09-10

Status: **ACTIVE DIAGNOSTIC / NO OPTIMIZATION**

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
3. `config_registration`: through `AllConfigs.register` return;
4. `package_and_schematic_defaults`: through `AllSchematicStateFilters.registerDefaults` return;
5. `bogey_non_thread_safe`: through `AllBogeyStyles.init` return, intentionally keeping the upstream non-thread-safe bogey region intact;
6. `compat_and_milk`: through `NeoForgeMod.enableMilkFluid` return;
7. `listener_and_optional_compat_tail`: through stock `onCtor` return, intentionally keeping listener order and final optional compatibility callbacks intact.

The analyzer requires exactly those six intermediate endpoints, in that order, on the same immutable FML worker thread, nested inside the single `create` FML construction interval. It also requires the PR #238 dependency subchain `colorwheel -> flywheel -> ponder -> create -> ratatouille`. The seven phase walls must tile `Create.onCtor` exactly; they are never summed with overlapping FML nodes.

## Semantics and interpretation

The boundaries are observer calls after existing method returns. They do not call registration methods, wrap an event bus, move a callback, replace a future/executor, or modify publication. Trace events remain in memory until shutdown as in #238.

A large phase remains only an investigation target. In particular, registry-family and configuration phases mutate registries/builders/listeners and can trigger class initialization; the bogey and final optional-compat regions have explicit upstream thread-safety warnings. No phase is classified pure merely from elapsed time. A future prepare -> barrier -> commit proposal is allowed only if source and runtime evidence identify a material product that is independent of registration order, class initialization, callbacks, mutable registries/builders, and failure/publication order.

## Hosted gate

One exact-pack hosted smoke uses the pinned `exact-pack-2026-09-02-v1` fixture, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`, Oracle JDK 25.0.4, `-XX:ActiveProcessorCount=4`, Xvfb/llvmpipe, and pinned JCEF commit `a78e832f9f13c2c688caea3d04d8b84fcd238d94`.

Required validity gates: build succeeds; main menu reached; `bootoptim_mixin_errors=0`; resource selection matches exactly with `reload_count=1`; FML is exactly `fml_loader@4.0.43`; Create is exactly 6.0.10; all FML chain invariants from #238 hold; all Create phase boundaries are present, ordered, same-thread, and tile `onCtor`.

Profile results and the final safe-frontier decision are appended only after a contract-valid run.

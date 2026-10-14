# Architectural boot rewrite programme — 2026-09-08

Status: **ACTIVE / programme plan**

The project objective is not a collection of micro-optimizations. The exact modpack currently has
startup costs that are unreasonable on both the fast PC and the old laptop. BootOptim is therefore
allowed to change NeoForge, ModLauncher, Minecraft, or user-owned mod forks when the final game is
observably equivalent after loading.

## Required end state

The cost of boot should scale with real work rather than multiplying superlinearly with the number
of mods. A successful change preserves registries, tags, recipes, models, textures, shaders, entity
rendering, callback contracts and ordering, and must pass menu, world, first-use and steady-state
validation. OpenGL/render-thread ownership, cache invalidation and fail-open behavior are mandatory.

## Work sequence

1. **Trace and sidecar contract.** Record phase, task, barrier, callback, resource and commit
   boundaries with one measurement origin. Development mode may expose the stream to an external
   viewer; benchmark mode must keep overhead bounded.
2. **Scaling matrix.** Run the exact pack with the baseline, full pack, each high-value mod and
   interaction groups. Compare bootstrap, discovery, resource reload, model/atlas, renderer,
   native and first-consumer vectors on CI and the physical laptop.
3. **Causal attribution.** For each large delta, decide whether it is actual CPU/parse work,
   filesystem/page-cache pressure, class transformation, executor contention, ordered apply, or a
   native/GPU boundary. Inclusive listener/task sums never become critical-path claims.
4. **Optimization ladder.** Try configuration/resource changes, direct fixes in controlled mod
   forks, reload-local caches, safe prepare/commit splits, persistent caches, and only then deep
   NeoForge/Minecraft loader redesign. A design that merely reduces counters while leaving the
   menu endpoint unchanged is limited evidence, not a promotion.
5. **Promotion gates.** Require compile/package, hosted exact-pack A/B, physical hardware evidence
   when storage/CPU/native behavior matters, and semantic validation of the affected content.

## Non-goals

Do not conceal errors, skip mandatory mod callbacks, move GL work to workers, assume CI hardware is
the laptop, or change the user's Java/OS installation. Hardware-sensitive policies may be adaptive
or configurable when they have a demonstrated benefit and no harmful default effect.

The durable research ledger remains the authority for failed experiments and reopening criteria;
`docs/optimizations/` is reserved for mechanisms that are actually retained for shipping.

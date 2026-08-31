# BootOptim agent operating guide

This file is mandatory project context for any AI/automation working on BootOptim. Its purpose is to prevent duplicated research, lost optimizations, accidental work on stale branches, and conclusions based on incomplete repository history.

## Mandatory first steps before changing code

Before proposing, implementing, or profiling any startup optimization:

1. Refresh and inspect the current `agent/integration-current` HEAD. Do not assume a SHA from an earlier conversation is still current.
2. Read this file, `README.md`, `docs/research/README.md`, every research document relevant to the subsystem, and `docs/optimizations/README.md` if it exists.
3. Inspect open **and closed** PRs touching the same subsystem. Read their bodies/comments/results, not only titles. An experiment being successful does **not** mean it was merged; a closed PR can contain important rejected evidence.
4. Verify the actual source tree on `agent/integration-current`. The source tree is the authority for what is currently integrated. If a validated optimization exists only in another branch/PR, it is not production yet.
5. Check whether there is already an active diagnostic or promotion branch that overlaps the work. Do not create a second profiler for measurements the repository already contains unless a new premise requires different instrumentation.

Failure to do these steps is considered a project-process bug.

## Branch discipline

- Never modify `main`.
- `agent/integration-current` is the integration branch and should contain only production code, durable documentation, and fixes that are ready to keep.
- New diagnostics/experiments/promotions start from the latest integration HEAD on their own branch and target `agent/integration-current` with a PR.
- Diagnostic/profiling code is not merged into integration merely because CI is green.
- Before merging a production PR, refresh integration again and confirm the PR still contains only intended changes.

## Validation hierarchy

A green build is necessary but not sufficient.

1. Compile/package CI must pass.
2. Startup CI must reach the main menu without new BootOptim/Mixin failures.
3. Behavior-changing optimizations require exact-reference-pack runtime validation by the user.
4. Performance claims require comparable warm/cold conditions and must distinguish CPU time, inclusive wall time, and actual critical-path wall time.
5. A microphase improvement is not accepted as an end-to-end win unless it moves time-to-main-menu or removes CPU for a mechanism the project deliberately chooses to keep.

The distributable JAR is the packaged bootstrap from `bootstrap/build/libs/`. A normal `./gradlew build` is expected to produce it. The root `build/libs` JAR is the inner regular mod and is not the standalone distributable.

## Durable project memory

Two documentation trees have different purposes:

- `docs/research/` records profiling, failed/limited experiments, architectural findings, exact measurements, and reopening criteria.
- `docs/optimizations/` records optimizations that the project intends to ship/retain, including safety invariants, kill switches, version assumptions, and measured evidence.

Every substantial experiment must leave a durable research entry even when rejected. Every promoted optimization must have a production catalog entry. If a later user decision overrides an earlier reject/keep decision, document the override explicitly so the older historical entry cannot be mistaken for the current policy.

## Current high-value historical traps

These are reminders, not a substitute for reading the ledger:

- ModelManager top-level bake identity repetition was already investigated. PR #36 removed about 64.57% of eligible repeated top-level calls but improved `bakeModels` only about 0.413 s / 4.7% and did not improve end-to-end startup. Do not repeat count-based identity caching as though it were unexplored.
- Simple eager top-level model-bake parallelism was already tried in PR #14. It improved the isolated bake but regressed main-menu time. A future parallel design must change the architecture/scheduling premise.
- Mixin side-load memoization in PR #43 was rejected: only 375/10,120 hits and roughly 41.7 ms estimated saved for ~55 MiB retained. Do not revive it as a major startup candidate.
- FancyMenu panorama preloading was a validated scheduling win: prelaunching the six existing async PNG suppliers reduced the measured synchronous panorama preload from about 8.31 s to about 2.57 s in the reference pack. This optimization is intentionally retained/promoted.
- Decocraft quarter-turn BBModel reuse removed about 2.7 s of repeated geometry CPU in its original experiment. Although the original PR #37 was initially rejected under the then-current significance threshold because only ~1 s reached critical path, the project later explicitly decided to retain the **hardened** production version with strict fail-open guards. Do not treat the old #37 rejection as the current product decision.
- Resource-reload listener durations from `ProfiledReloadInstance` are inclusive/overlapping. Do not sum them. PR #47 introduced barrier/turn critical-path profiling because ModelManager needed to be proven as the actual preparation gate.

## Safety / compatibility invariants

- Preserve stock observable behavior unless the optimization has an explicitly validated equivalence argument.
- Optional mod optimizations must fail open and must not create hard runtime dependencies.
- Do not move OpenGL/render-thread-only work to background threads.
- Caches require explicit invalidation/lifetime rules.
- Threading changes must account for mod callbacks and non-thread-safe custom loaders, not just vanilla collections.
- Prefer redesigning a real bottleneck over accumulating many fragile micro-patches.

## Working style

Investigate root cause first, then implement. Keep diagnostic and production mechanisms separate. Record confirmed cause, hypotheses, measurements, residual risks, and whether runtime behavior was actually validated. Significant architectural wins are preferred, but medium/small improvements can be retained when their safety/maintenance cost is low or the project explicitly chooses to keep them.

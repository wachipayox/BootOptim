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
3. Hosted exact-pack CI should be the default next runtime gate for changes that can be exercised there; see `docs/research/exact-pack-ci.md`.
4. Behavior-changing optimizations still require semantic/visual validation where the hosted workflow cannot prove equivalence.
5. Performance claims require comparable conditions and must distinguish CPU time, inclusive wall time, and actual critical-path wall time.
6. A microphase improvement is not accepted as an end-to-end win unless it moves time-to-main-menu or removes CPU for a mechanism the project deliberately chooses to keep.
7. Real laptop/fast-PC runs remain the final gate for small/noisy effects and hardware-sensitive mechanisms, but agents should not ask for repetitive laptop A/B runs when hosted exact-pack CI can reject or validate the premise first.

The distributable JAR is the packaged bootstrap from `bootstrap/build/libs/`. A normal `./gradlew build` is expected to produce it. The root `build/libs` JAR is the inner regular mod and is not the standalone distributable.

## Hosted exact-pack CI

The project has a pinned public exact-pack fixture and a hosted startup workflow. Read `docs/research/exact-pack-ci.md` before requesting manual exact-pack hardware runs.

Current fixture contract:

- release/tag: `exact-pack-2026-09-02-v1`
- asset: `bootoptim-exact-pack.zip`
- SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`
- fixture contains the user's enabled resource-pack state and preseeded `mods/mcef-libraries/`;
- fixture intentionally excludes mutable `mods/mcef-cache/`;
- laptop JVM baseline is Oracle Java 25.0.4, `-Xmx6G`, G1 with the user's supplied tuning, plus `-XX:ActiveProcessorCount=4` on hosted runners.

To request a hosted smoke run for a PR targeting `agent/integration-current`, put this in the PR body:

```text
[exact-pack-ci]
exact-pack-mode: smoke
```

For a same-branch A/B where a JVM property selects candidate/control behavior:

```text
[exact-pack-ci]
exact-pack-mode: ab
exact-pack-repetitions: 3
exact-pack-candidate-jvm-arg: -Dboot_optim.exampleFeature=true
exact-pack-control-jvm-arg: -Dboot_optim.exampleFeature=false
```

Multiple candidate/control JVM-arg lines are allowed. Each A/B run gets a fresh hosted VM. The workflow aggregates medians and uploads `latest.log`, `bootoptim-startup.log`, effective config/options, timeout dumps, and a compact result JSON.

Hosted exact-pack CI is a reproducible **software-pack surrogate**, not the historical laptop itself. Do not overclaim exact hardware equivalence. A physical laptop gate is still required when the mechanism materially depends on storage/page-cache behavior, native/GPU timing, or when the hosted delta is small relative to variance. Large coherent hosted wins should be used to avoid unnecessary manual laptop iteration.

## User-owned / user-edited mod lane

The user explicitly permits direct modifications to mods they authored or edited.

If repository/source/history shows that the user is the author of a mod, or a pack JAR is a user-maintained/custom fork such as a `-wedit` build, **do not assume BootOptim must work around that mod from the outside**. A direct source change in the controlled mod is a valid optimization candidate and may be cleaner/safer than a BootOptim mixin.

When this applies:

- tell the user which direct-mod change you propose and why;
- ask for/evaluate the relevant mod repository or source if it is not already accessible;
- keep that change isolated to the appropriate mod repository rather than silently vendoring it into BootOptim;
- still apply the same correctness, startup, exact-pack, and regression gates;
- do not treat user ownership as permission to bypass semantic or visual validation.

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

## Local relay coordination with external agents

When the user has enabled the local relay workflow, the primary Codex agent must maintain the ignored `.agent-coordination/` directory. It is operational memory, never a Git artifact.

- Read `.agent-coordination/COORDINACION.md` before delegating or replying to a relay result. Update it immediately after assigning, receiving, redirecting, closing, or superseding an agent task.
- Start every new relay agent from `.agent-coordination/PLANTILLA_PROMPT.md`. Fill in the concrete subsystem, current integration SHA/branch, relevant PRs/docs, established measurements, non-goals, decision gate, and expected response. Agents do **not** have this local workspace or this conversation; provide GitHub URLs and all decision-relevant context in their prompt.
- Each agent folder contains `contexto.md` (durable compact conversation/status), `prompt.txt` (the next exact text for the user to relay), and an optional `res.txt` (the agent's latest reply). After reading `res.txt`, incorporate the facts/decision into `contexto.md` and `COORDINACION.md`, then remove `res.txt` so it cannot be processed twice.
- Do not overwrite or repurpose an active agent's `prompt.txt`. Create a new numbered agent for a new bounded task. Reuse an existing agent only when its currently assigned investigation is complete and the next task is a direct continuation.
- Before asking the user to relay any new/changed prompt, write the files first and issue a Windows notification naming exactly the agents to send. Do not stop local investigation while those agents work.
- Keep relay tasks bounded and evidence-oriented. They may research, create branches and use GitHub Actions, but must not assume unpushed local changes, laptop state, private files, or authorization beyond the prompt.
- On completion, record the resulting PR/commit, evidence and disposition in both coordination memory and the project research ledger as appropriate; delete an agent folder only when it will not be reused.

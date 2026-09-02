# Hosted exact-pack startup CI

Status: **ACTIVE INFRASTRUCTURE**

This document defines the reproducible hosted surrogate for BootOptim's exact reference modpack. Its purpose is to eliminate most manual laptop launches while preserving the project's requirement that startup work be tested against the real software pack rather than a vanilla/dev-only client.

Hosted exact-pack CI is a **surrogate**, not a claim that GitHub hardware is the Intel i3-2350M laptop. Hardware-sensitive effects still require a real-hardware gate when the hosted effect is small, when storage/page-cache behavior is the mechanism, or when native/GPU behavior materially controls the result.

## Fixture pin

The authoritative fixture is a public BootOptim Release asset:

- release/tag: `exact-pack-2026-09-02-v1`
- asset: `bootoptim-exact-pack.zip`
- asset size: `1,202,581,263` bytes
- SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`

The workflow verifies the SHA-256 before extracting the fixture and caches the ZIP by the pinned digest. Never retarget this tag/asset silently. A changed pack gets a new release/tag and a new documented hash.

The ZIP was assembled from the user's PC rather than the laptop. It is the authoritative **software pack** fixture: exact mod/config/resource-pack contents supplied by the user, including the resource packs they normally keep enabled. Laptop hardware characteristics are approximated separately by the benchmark JVM/runtime settings.

## MCEF baseline

The fixture deliberately includes `mods/mcef-libraries/` and deliberately excludes `mods/mcef-cache/`.

Rationale:

- native CEF libraries are pinned inputs and must not introduce network/download/extraction variance on each hosted VM;
- Chromium browser cache is mutable runtime state and would make runs depend on prior navigation/history;
- each hosted benchmark therefore starts with preseeded CEF binaries but a cold browser cache.

`scripts/exact-pack/prepare-fixture.ps1` fails if this invariant changes.

## Laptop JVM surrogate

The user-provided laptop JVM arguments are reproduced for the Minecraft process:

```text
-Xmx6G
-XX:+UnlockExperimentalVMOptions
-XX:+UseG1GC
-XX:G1NewSizePercent=20
-XX:G1ReservePercent=20
-XX:MaxGCPauseMillis=50
-XX:G1HeapRegionSize=32M
```

Hosted CI additionally sets:

```text
-XX:ActiveProcessorCount=4
```

because the reference laptop has four logical processors and a hosted runner must not expose a different processor count to Minecraft/ForkJoinPool heuristics.

The reference runtime is Oracle JDK `25.0.4`, matching the user's current laptop runs. BootOptim remains compiled with its production Java 21 toolchain; only the `runPackBenchmarkClient` Java launcher is overridden to the exact-pack runtime when `BOOTOPTIM_PACK_JAVA_VERSION` is set.

## What the fixture copies

`preparePackBenchmark` copies startup-relevant instance content into `run-pack-benchmark`, including:

- `mods/`
- `config/`
- `defaultconfigs/`
- `automodpack/`
- `resourcepacks/`
- `shaderpacks/`
- `kubejs/`
- `scripts/`
- `paxi/`
- `openloader/`
- `global_packs/`
- `fancymenu_data/`
- `options.txt`

BootOptim itself must not be present in the fixture. The source/PR build is injected by ModDevGradle, ensuring an A/B tests exactly the branch under review.

## Workflow trigger protocol

Workflow: `.github/workflows/exact-pack-startup-benchmark.yml`.

Because `agent/integration-current` is not the repository default branch, agents must not rely on `workflow_dispatch` being available for integration-only workflow revisions. The durable PR trigger is the PR body marker:

```text
[exact-pack-ci]
exact-pack-mode: smoke
```

A smoke run uses one fresh Windows hosted VM and current feature defaults.

For same-branch A/B, use:

```text
[exact-pack-ci]
exact-pack-mode: ab
exact-pack-repetitions: 3
exact-pack-candidate-jvm-arg: -Dboot_optim.exampleFeature=true
exact-pack-control-jvm-arg: -Dboot_optim.exampleFeature=false
```

Multiple `exact-pack-candidate-jvm-arg:` / `exact-pack-control-jvm-arg:` lines are allowed. The workflow creates independent hosted VMs for each run, alternating logical matrix entries as control/candidate and aggregating medians. Repetitions are restricted to 1-5 per variant to bound CI cost.

Editing the PR body or pushing a new commit triggers the workflow while `[exact-pack-ci]` remains present. Remove the marker when continuous exact-pack reruns are no longer wanted.

`workflow_dispatch` is also defined for use if/when the workflow is available on the repository default branch, but PR-body triggering is the integration-branch contract agents can rely on today.

## Produced evidence

Each run uploads only lightweight diagnostics rather than the copied pack:

- `result.json`
- exact-pack console log
- timeout thread dump when present
- `run-pack-benchmark/logs/latest.log`
- `run-pack-benchmark/logs/bootoptim-startup.log`
- effective `boot_optim.properties`
- effective `options.txt`

The aggregate job reports medians for available markers including:

- total/main-menu startup uptime;
- mod-entrypoint uptime;
- mod-entrypoint -> main-menu wall;
- MCEF initialization wall;
- initial resource-reload start -> FancyMenu reload-finished wall;
- FancyMenu panorama wall when a compatible marker is present;
- BootOptim Mixin failure count;
- Decocraft 3D-item markers are retained verbatim in each run result.

Do not sum concurrent/asynchronous listener or task-sum measurements. Hosted CI remains governed by the same critical-path rules as laptop profiling.

## Interpretation and hardware gate

Use hosted exact-pack CI as the default first runtime/performance gate after Build CI and vanilla Startup CI.

A large, coherent hosted win across repeated A/B runs can eliminate most manual laptop iteration. A laptop/fast-PC confirmation remains appropriate before production promotion when:

- the expected gain is small relative to run variance;
- physical disk access, OS page cache, storage queueing, or filesystem behavior is the mechanism;
- native CEF/GPU behavior is central;
- steady-state visual/render behavior must be inspected;
- the hosted runner and laptop disagree materially in phase scaling.

The goal is not to make hosted wall time equal the historical ~337 s laptop wall. The goal is a reproducible exact-software-pack environment that predicts direction and exposes phase regressions before scarce hardware runs.

## Editable/custom mod lane

The user explicitly permits direct modifications to mods they authored or edited. When source/history indicates a mod is authored by the user or is a user-maintained/custom `-wedit` fork, agents may treat **editing that mod directly** as a valid optimization candidate instead of assuming BootOptim must work around it externally.

Agents should surface the proposed direct-mod change to the user for evaluation, keep the change isolated to the appropriate repository, and still apply normal correctness/performance validation. This permission does not waive semantic safety; it removes an artificial repository-boundary constraint when the user controls the relevant mod.

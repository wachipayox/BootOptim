# ModernFix reload parallelism lease audit (2026-09-06)

Status: **REJECTED AS A PRODUCTION OPTIMIZATION; retain only as inconclusive hardware-sensitive evidence**.

Related PRs: #14, #47, #109, #117, #120, #122, #124, #126.

## Question

PR #126 tested a temporary Java 21 `ForkJoinPool.setParallelism(3 -> 2)` lease on ModernFix 5.27.14+mc1.21.1's dedicated resource-reload executor during the first client reload. Its hosted 3x3 cohort medians looked favorable overall, but the direct reload-local movement was only `-242 ms`. This audit asks whether there is a genuinely adaptive policy or architectural correction that turns that signal into a reproducible TTMM win without repeating eager model parallelism.

## Exact mechanism and compatibility boundary

ModernFix's dedicated reload executor is a separate `ForkJoinPool` created with `ForkJoinPool.getCommonPoolParallelism()`, a custom worker factory/context class loader, `asyncMode=true`, and `Worker-ResourceReload-*` worker names. Its Minecraft mixin redirects both initial client reload and `reloadResourcePacks` to the same global service. PR #126 correctly avoids replacing that executor and acts only after exact ModernFix version and executor-identity checks succeed.

The #126 lease preserves executor identity, task/future/barrier objects and ordering, changes only the target parallelism, and restores the previous target once on terminal completion. It is default-off and fail-open. Those properties make the experiment substantially safer than a replacement executor, semaphore wrapper, or global common-pool change.

One lifetime detail is architecturally broader than necessary: #126 restores at `allDone`, while the mutated executor is the `prepareExecutor`. `SimpleReloadInstance` exposes `allPreparations` separately from `allDone`; once global preparation completes, retaining the reduced ModernFix target through the ordered apply tail no longer serves the preparation-side hypothesis. A future reimplementation should therefore restore on `allPreparations` terminal completion, with `allDone` only as a defensive fallback if required. This is a compatibility/scope correction, not evidence of more speed.

## Hosted #126 evidence, separated by metric type

Run `33988912109`, summary artifact `9976114155`, completed all six exact-pack runs with `8192x8192x2`, zero BootOptim Mixin errors, and successful restoration in every candidate sample.

Cohort medians reported by #126:

| Metric | candidate | control | delta | interpretation |
| --- | ---: | ---: | ---: | --- |
| TTMM wall | 90,401 ms | 94,036 ms | -3,635 ms | not attributable: large pre-reload drift |
| mod-entrypoint wall | 30,364 ms | 31,918 ms | -1,554 ms | occurs before mechanism |
| post-mod wall | 60,037 ms | 61,662 ms | -1,625 ms | includes changed and unrelated work |
| MCEF wall | 1,097 ms | 2,114 ms | -1,017 ms | unrelated variance |
| reload -> FancyMenu wall | 42,613 ms | 42,855 ms | -242 ms | direct primary interval; only -0.56% |
| panorama wall | 4,099 ms | 4,258 ms | -159 ms | downstream/non-target |

The per-sample `result.json` files make the reload-local result weaker than the cohort medians imply:

- iteration 1: candidate `42,613 ms`, control `43,407 ms` => `-794 ms`;
- iteration 2: candidate `34,255 ms`, control `28,844 ms` => `+5,411 ms` regression;
- iteration 3: candidate `43,468 ms`, control `42,855 ms` => `+613 ms` regression.

Likewise TTMM per iteration is `90,401 vs 95,400`, `72,793 vs 66,583`, and `93,869 vs 94,036 ms`. The extreme fast second control sample materially determines the cohort median picture. Hosted variance is therefore larger than the measured direct effect, and two of three same-index reload-local comparisons regress.

No process-CPU or reload-worker CPU metric is present in #126's `result.json`; the logs only establish `processors=4` and lease state/restoration. Therefore #126 cannot show that 3 -> 2 removes CPU contention even if its wall median survives.

## Physical laptop replication

The final Windows laptop was kept logged in and was not rebooted. Runs `025`/`027` used the same #126 artifact and exact-pack instance, with lease first and control second; runs `028`/`029` repeated the same artifact in reverse order (control first, lease second). Every run reached the main menu, logged successful lease restoration, and the original JAR/config were restored after the experiment.

| order | run | lease | main menu | mod entrypoint | reload → FancyMenu |
| --- | --- | --- | ---: | ---: | ---: |
| lease → control | `025` | on | 427.170 s | 104.476 s | 231.206 s |
| lease → control | `027` | off | 535.912 s | 193.159 s | 254.963 s |
| control → lease | `028` | off | 444.332 s | 154.618 s | 218.405 s |
| control → lease | `029` | on | 676.432 s | 365.481 s | 216.863 s |

The first pair showed a `-23.757 s` reload interval and `-20.059 s` post-entrypoint movement for the lease, but `-88.683 s` of the total difference was already before mod entrypoint. The reversed pair showed only `-1.542 s` in reload interval while the lease run was `+232.100 s` slower overall and `+210.863 s` slower before mod entrypoint. The direct physical sign is therefore not reproducible; native MCEF/download, page-cache and other pre-entrypoint state dominate these no-reboot runs. This strengthens the no-promotion decision rather than supplying a hardware activation rule.

## Why an adaptive controller is not justified

There is no stable feedback signal available before the initial reload that predicts whether two or three workers will reduce critical wall:

- `availableProcessors()==4` describes the JVM scheduling budget, not physical-core topology or current renderer/native/common-pool contention;
- ForkJoin queue depth/task count cannot distinguish CPU-heavy, I/O-heavy or blocking listener work and `setParallelism` is a target rather than a strict cap;
- process CPU sampled after contention starts would lag behind phase changes and risks oscillating between ModelManager, atlas/resource I/O and other preparation listeners;
- hosted #126 does not establish even a stable sign for the static 3 -> 2 change, so a controller would tune around noise rather than a demonstrated transfer function.

Persistent self-calibration across launches would add hardware/workload identity, invalidation and first-run policy complexity far beyond the evidence. Physical-core/SMT detection would also make activation OS/topology-dependent without proving that topology, rather than workload mix, is the causal discriminator.

## Relation to prior evidence

PR #14 already showed that increasing model-bake concurrency improved `bakeModels` and ModelManager while regressing main-menu wall. It is therefore not valid to infer TTMM from worker utilization or one concurrent subphase.

PR #47 established that inclusive listener durations overlap and that preparation-barrier/apply-turn timing is required for critical-path claims. The ModernFix lease is producer-side preparation scheduling; FancyMenu `preLoadAll` is later on the apply/render side, so the known FancyMenu waiter CPU is not evidence for this pool policy.

PR #117 further showed that a dramatic local CPU reduction in FancyMenu waits can still regress reload->FancyMenu and TTMM. CPU savings are useful only if tied to the enclosing critical interval.

## Risk assessment

### Concurrency / semantics

- `ForkJoinPool.setParallelism` changes a target, not a hard maximum; blocked tasks may trigger compensation.
- Lowering the target can starve a preparation gate if ModelManager/atlas/resource parsing is actually throughput-bound.
- The executor is global ModernFix state and is reused by later/manual reloads. One-shot acquisition and exact restoration are mandatory.
- A restore-at-`allPreparations` lifetime is preferable to #126's restore-at-`allDone` because it limits mutation to the executor's relevant stage.

### ModernFix compatibility

A future experiment must remain optional and exact-version/identity guarded as #126 is. It must never create a hard ModernFix dependency, replace ModernFix's worker factory/context class loader/async mode, touch the common pool globally, or persist the lower target beyond initial preparation. Unknown ModernFix versions or executor shapes must fail open.

### Visual/gameplay

The lease does not move OpenGL/render-thread work and #126's semantic smoke reached title with the exact atlas and resource-selection invariants. The remaining risk is scheduler timing/throughput and interactions with third-party preparation listeners, not intentional gameplay or visual changes.

## Decision

**Close the 3 -> 2 lease as a production candidate. Do not promote with a kill switch and do not build a dynamic/adaptive controller.**

Reason: the only directly relevant hosted wall metric moves `-242 ms` at cohort median while per-sample reload-local comparisons are mixed and mostly negative; TTMM is dominated by unrelated cohort drift; and no CPU metric demonstrates the intended contention mechanism. There is no stable input from which to derive a safe adaptive policy.

Retain #126 as inconclusive hardware-sensitive evidence only. The concrete hardware prediction, if this lane is ever reopened, is narrow: a true 2-physical-core/4-thread machine with software-render/native/common-pool competition may benefit from reserving one logical slot where hosted 4-vCPU runners do not. The physical reversed pair did not reproduce the first signal, so another runtime branch is not justified now.

Reopening requires **new evidence**, not another 3x3 of the same patch: a small physical discriminator on the exact 2C/4T target showing a repeatable improvement in reload->FancyMenu and TTMM together with process/reload CPU reduction. Any reimplementation should restore at `allPreparations`, preserve #126's exact executor/version/fail-open guards, and then pass one semantic smoke plus an interleaved A/B. Without that evidence, the lane remains closed.

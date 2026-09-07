# ModernFix initial reload 3→2 lease — isolated replay closure (2026-09-08)

Status: **REJECTED / NO-GO FOR A NEW RUNTIME CANDIDATE**.

Base audited: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

Related: #124, #126, #132, #138, #141, #178 and `modernfix-reload-lease-audit-2026-09-06.md`.

## Question

Re-test only the narrow hypothesis from PR #124: on the exact pack, ModernFix `5.27.14+mc1.21.1` owns a dedicated client resource-reload `ForkJoinPool` whose observed target parallelism is 3 when the JVM sees four processors. Could a default-off, first-reload-only temporary target-parallelism lease `3 -> 2` reduce contention or storage pressure on constrained hardware while leaving executor identity, callbacks, futures, GL/application-thread work and the global common pool untouched?

The requested decision gate was to use PR #178's isolated phase replay before creating another runtime implementation. If the replay made the policy obviously worse, the lane was to be closed without another exact-pack or laptop A/B.

## Historical boundary

PR #126 already implemented this exact runtime mutation using Java 21 `ForkJoinPool.setParallelism(2)`, with exact ModernFix version/executor identity guards, default-off activation and one-shot restoration. Its hosted 3x3 moved the directly relevant `reload -> FancyMenu` median only `-242 ms` (`-0.56%`), while two of three same-index reload-local comparisons regressed. The larger apparent TTMM delta was dominated by unrelated pre-entrypoint/MCEF cohort drift.

The later physical laptop pairs were not coherent enough to reopen the premise. One order showed `-23.757 s` reload-local movement; the reversed order showed only `-1.542 s` while unrelated pre-entrypoint drift dominated total startup. The durable audit therefore closed `3 -> 2` as a production candidate and required new evidence rather than another copy of the same patch.

PR #132 separately establishes that safe ModelManager preparation branches are already overlapped and that atlas upload/publication cannot be moved earlier without changing live texture-generation semantics. PRs #138/#141 further show that ModelManager, atlas preparation and bake/load are distinct overlapping barriers; inclusive listener or profiler totals must not be summed.

## PR #178 isolated replay used as the discriminator

Source replay: PR #178 head `2b2ffc6e58fecb8c56f0d04b2a018cbc1a1c751f`, workflow run `34162510177`.

Fixture artifact: `isolated-phase-fixture` artifact `10033066127`, fixture id `exact-pack-model-graph-767328e84de51424`.

Fixture metadata includes:

- `74,561` replay tasks;
- `32,081` models and `10,397` blockstates;
- selected exact-pack resource packs from the pinned fixture;
- calibrated work-family totals from the physical ModelManager run (`block_states=9657.986`, `block_models=24960.395`, `bake_models=41152.538` work units).

The replay is deterministic and reports scheduling work units, not TTMM. That limitation is intentional: this layer can reject an obviously throughput-starving scheduling policy, but cannot promote one.

Using PR #178's stock-order scheduling algorithm on the immutable fixture:

| stock policy | workers | makespan units | queue-wait units | speedup vs single |
| --- | ---: | ---: | ---: | ---: |
| stock | 1 | `75,770.919305` | `1,275,495,671.226` | `1.0000x` |
| stock | 2 | `37,942.608805` | `630,461,175.368` | `1.99699x` |
| stock | 3 | `25,370.531591` | `420,281,223.824` | `2.98657x` |
| stock | 4 | `19,084.529452` | `315,191,375.683` | `3.97028x` |

The requested `3 -> 2` policy therefore increases replay makespan by `12,572.077214` work units, **+49.55%**, and aggregate queue wait by about **+50.01%**.

This is not a claim that a real HDD/ForkJoinPool must regress by 49.55%: the replay does not model page-cache misses, rotational seek latency, ForkJoin compensation, GC, native renderer contention or OS descheduling. It is, however, exactly the requested pre-runtime rejection test, and it says the lower worker target is strongly throughput-starved under the current captured dependency/work graph.

## Hook assessment

A maintainable hook does exist in principle: #126 proved that BootOptim can observe the exact ModernFix preparation executor and call `ForkJoinPool.setParallelism` without replacing the pool. The previous audit also identified a narrower lifetime improvement: restore at `allPreparations` rather than retaining the lease through `allDone`, with `allDone` only as defensive fallback.

That hook quality does **not** justify another implementation here. The premise has not changed materially enough to repeat #126:

- same ModernFix version and dedicated pool;
- same `3 -> 2` mutation;
- same four-visible-processor activation case;
- no new measured CPU/queue/IO discriminator proving contention relief;
- isolated replay now gives a strong negative throughput signal;
- previous hosted and physical evidence was already mixed/noisy.

Creating a second runtime branch with only the narrower restoration boundary would be a lifecycle-scope cleanup, not a new performance premise. It would repeat a rejected experiment contrary to the research ledger.

## Decision

**No runtime code was implemented. No lifecycle tests, exact-pack smoke, hosted 3x3, or laptop run are requested.**

The isolated replay fails the first requested gate by a wide margin, while historical #126 evidence supplies no coherent counter-signal strong enough to override it. A new `3 -> 2` lease is therefore closed as a no-go before runtime validation.

This decision also avoids conflating inclusive ModelManager/atlas/bake timings with recoverable wall time and does not reopen FancyMenu busy-wait work from #117.

## Reopening criteria

Do not reopen with another static `3 -> 2` patch or another fresh-VM 3x3 alone. Reopening requires a materially new premise, for example:

1. a captured physical task/dependency replay that includes storage-blocking/queue behavior and predicts a lower critical-path wall for two workers rather than merely lower CPU;
2. a low-overhead physical discriminator showing coherent reload-critical-path improvement together with process/reload-worker CPU or I/O evidence attributable to the lease;
3. a changed ModernFix executor architecture/version that invalidates the current throughput model.

If such evidence appears, reuse the safe design constraints from #126: explicit default-off property, exact version/executor identity guard, no hard ModernFix dependency, no common-pool mutation or replacement executor, one startup lease only, exact previous-parallelism capture, restoration on every terminal path with a bounded defensive lifetime, and fail-open behavior on any shape mismatch.

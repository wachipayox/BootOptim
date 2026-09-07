# GitHub fresh-VM variance and same-VM paired diagnostic (2026-09-07)

## Trigger

The `elements_cull_direction_cache` hosted A/B campaign produced a nominal
`-27,639 ms` candidate-minus-control median. The individual runs were not
coherent: candidate totals were `64,930`, `66,132`, and `91,575 ms`, while
controls were `93,771`, `95,727`, and `74,592 ms`. The third candidate was
`16,983 ms` slower than the third control. The same phase markers moved with
the total (`reload` and `post_mod_entrypoint`), and there was no Mixin failure,
so this is not evidence of a stable optimization.

The current workflow intentionally starts every matrix entry on a fresh
`ubuntu-22.04` VM. That isolates each cold launch, but it also allows runner
allocation, storage/page-cache state, scheduler contention, or host pressure
to dominate a small or medium effect. `max-parallel: 3` does not make those
runs paired: it only limits how many independent VMs are active.

## Diagnostic added

`exact-pack-startup-benchmark.yml` now accepts `exact-pack-mode: paired`.
Each repetition remains a fresh VM, but one matrix job launches both control
and candidate processes sequentially on that same VM. The order alternates by
pair (odd: control → candidate; even: candidate → control), so a warm-second
run bias is not permanently assigned to one variant. `run_paired.py` resets
benchmark logs, MCEF's mutable cache directory and the previous reports before
each process, copies each result/log/config into `paired-results/`, and removes
root-level copies to avoid double-counting during aggregation.

This is deliberately a diagnostic, not a production claim. It shares the
runner kernel, filesystem/page cache and Gradle caches, so it answers whether a
candidate/control effect survives a common host. It does not provide a cold
startup baseline and cannot emulate the Windows laptop's disk, native CEF or
GPU behavior.

## Decision gate

Do not promote or send a micro-optimization to the laptop because of a normal
fresh-VM median alone. First run paired mode with at least three alternating
pairs. A candidate is a credible lead only when:

1. within-pair deltas have the same sign in most/all pairs;
2. the magnitude is larger than the paired spread and relevant critical-path
   phase markers move consistently;
3. the mechanism marker is present in every candidate run and controls remain
   semantically equivalent; and
4. the result is still interpreted as a hosted surrogate and receives the
   physical-laptop gate when storage, native or Windows behavior is involved.

If paired control-only repeats still vary materially, treat the unresolved
runner bucket as the blocker and collect host telemetry before writing another
Java optimization. A nominal improvement with mixed paired signs remains
`INCONCLUSIVE`, not a reason to touch the laptop.

## First paired result (PR #154)

The first three alternating pairs completed successfully on fresh VMs. The
candidate-minus-control main-menu deltas were:

| Pair | Order | Candidate minus control |
| ---: | --- | ---: |
| 1 | control → candidate | −1,308 ms |
| 2 | candidate → control | +4,732 ms |
| 3 | control → candidate | −2,786 ms |

The ordinary per-variant medians were `94,737 ms` candidate and `96,045 ms`
control, but the within-VM signs are mixed. The corresponding
reload→FancyMenu deltas were `−355 ms`, `+2,104 ms`, and `−3,281 ms`; this
locates the residual noise in the resource-reload critical tail rather than in
the small candidate mechanism. Pair 2's candidate was the slower run even
though it launched first, so the effect is not explained by a simple
warm-second-run bias. The candidate is therefore still **INCONCLUSIVE** and no
laptop run is justified. The next paired diagnostic records `vmstat` pressure
(`r`, blocked I/O, swap, wait and stolen CPU) during both launches so the
remaining host/scheduler bucket can be tested directly.

## Host-pressure confirmation (PR #154, second campaign)

The paired campaign with `host-vmstat.log` reproduced mixed signs:

| Pair | Order | Candidate minus control |
| ---: | --- | ---: |
| 1 | control → candidate | +1,750 ms |
| 2 | candidate → control | −1,666 ms |
| 3 | control → candidate | +1,191 ms |

The traces identify the confounder rather than a Java mechanism. During the
resource-reload intervals the runner reached roughly 40–97% I/O-wait, with
blocked processes (`b`) and, in one pair, non-zero swap-out (`so`, with
`swpd` rising while free memory fell below roughly 200 MiB). Stolen CPU stayed
at zero, so the dominant disturbance is Azure runner storage/memory pressure,
not a competing hypervisor CPU allocation. This explains why fresh-VM A/B can
look like a tens-of-seconds win and why even same-VM pairs retain a few-second
tail difference. It is measurement contamination, not evidence that the
candidate changes ModelManager semantics.

Operational consequence: ordinary fresh-VM A/B is not a sufficient gate for a
small startup effect. Use same-VM paired runs with the host trace to reject
pressure-contaminated samples, and retain the physical laptop gate for any
storage/page-cache-sensitive conclusion. No BootOptim production code or
laptop state was changed as a result of this diagnostic.

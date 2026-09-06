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

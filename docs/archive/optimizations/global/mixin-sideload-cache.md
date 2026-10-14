# Mixin side-load transform memoization

Status: experimental, exact-pack runtime validation required before merge.

## Motivation

Real-pack profiling on Minecraft 1.21.1 / NeoForge 21.1.248 / Mixin 0.8.7 measured:

- `mixin.prepare`: ~3.181 s;
- `mixin.apply`: ~6.358 s;
- 10,120 recursively nested ModLauncher transforms with transform context `mixin`;
- ~1.302 s inclusive / ~1.271 s exclusive in those `context=mixin` transforms.

Mixin obtains class metadata through `IClassBytecodeProvider#getClassNode`, whose ModLauncher implementation calls
`ITransformerLoader#buildTransformedClassNodeFor(className)`. Mixin's own launch plugin deliberately declines class
processing while the transform reason is `mixin`, so these side-loads run the rest of ModLauncher's transformation
pipeline without applying target mixins recursively.

## Mechanism

BootOptim replaces only the already-initialized `mixin` entry in ModLauncher's launch-plugin map with a transparent
delegate. Every Mixin callback still goes to the original plugin object. During `initializeLaunch`, the delegate wraps
the `ITransformerLoader` supplied to Mixin in a process-local memoizing loader.

For each canonical class name:

1. the first request always executes the stock `ITransformerLoader` path;
2. successful transformed bytes are copied into the in-memory cache;
3. later requests for the same name in the same JVM receive a defensive copy of those bytes.

This is deliberately different from transformed-class-cache PR #22: it does not cache GAME class definitions and it
does not bypass `MixinTransformer#transformClass` for real target classes.

## Safety invariants

- Process-local only: nothing is persisted across launches.
- First request is always authoritative stock ModLauncher behavior.
- Exceptions, `null`, empty results and oversized entries are never cached.
- Cached byte arrays are immutable snapshots; callers receive clones.
- Default total cache budget: 64 MiB.
- Default per-entry limit: 4 MiB.
- Exact ModLauncher 11.0.5 and Mixin 0.8.7 guards.
- Installer failure leaves the stock launch-plugin path active.
- Kill switch: `-Dboot_optim.mixinSideLoadCache=false`.

Tuning properties, intended only for diagnostics:

- `boot_optim.mixinSideLoadCache.maxBytes`
- `boot_optim.mixinSideLoadCache.maxEntryBytes`

## Residual compatibility risk

A cache hit also avoids repeated callbacks of launch plugins other than Mixin which would otherwise run while servicing
that repeated side-load request. The first callback sequence is still preserved. The ModLauncher API exposes this
operation only by canonical class name, which suggests repeat requests are expected to be equivalent, but third-party
plugins can theoretically attach side effects to every invocation.

For that reason this optimization stays experimental until the exact production pack reaches the title screen, enters
a world successfully, and shows no launch/mixin regressions.

## Real-pack result: first validation

The first exact-pack run of PR #43 reached the main menu and reported:

- 10,120 calls;
- 375 hits (3.71%);
- 9,745 misses (96.29%);
- 9,686 final cache entries;
- 55.131 MiB retained in the cache;
- 1.447 MiB actually served from cache hits;
- 2,498.011 ms accumulated delegate time across misses;
- 0 bypasses.

Because there were 9,745 misses but only 9,686 final entries, with no bypasses, 59 miss calls raced another thread and
lost `putIfAbsent` after both had already delegated. Even a perfect single-flight implementation would therefore have
raised reuse by call count only from 3.71% to at most about 4.29% in that run.

The aggregate hit rate is too low to justify production adoption by itself, especially given the residual launch-plugin
callback risk and ~55 MiB retained for ~1.45 MiB of served hits. However, call-count hit rate alone cannot prove the
experiment worthless: a small number of repeated classes could still dominate delegate time.

The final diagnostic pass therefore leaves cache semantics unchanged and records, per cached class, the first successful
delegate duration, hit count, duplicate-race count and race delegate time. It reports both top classes by hit count and
by `first delegate duration * hits`, plus an aggregate estimated hit-time saving. This estimate is deliberately labeled
as such because a cold first delegate can cost more than a later uncached invocation.

## Metrics

`BOOTOPTIM_MIXIN_SIDELOAD_CACHE` reports:

- calls, hits, misses and hit rate;
- number and size of cached entries;
- bytes served from cache;
- total/average/max delegate miss time;
- bypasses caused by invalid or oversized results;
- `estimated_hit_saved_ms`, using each cached class's first successful delegate duration multiplied by later hits;
- `raced_misses` and `race_delegate_ms`, measuring duplicate work caused by concurrent misses before insertion;
- `max_reuse_percent`, the observed hit rate plus raced misses, as a call-count ceiling for single-flight;
- top repeated classes by estimated saved time and by hit count.

The real-pack profiler gives a rough upper bound of ~1.3 s for eliminated nested ModLauncher transformation work.
Actual end-to-end savings depend on duplicate side-load rate and critical-path overlap. Unless the final per-class
metrics show that the small hit set is disproportionately expensive, this experiment should be rejected rather than
expanded.

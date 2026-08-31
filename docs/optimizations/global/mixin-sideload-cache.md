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

## Metrics

`BOOTOPTIM_MIXIN_SIDELOAD_CACHE` reports:

- calls, hits, misses and hit rate;
- number and size of cached entries;
- bytes served from cache;
- total/average/max delegate miss time;
- bypasses caused by invalid or oversized results.

The real-pack profiler gives a rough upper bound of ~1.3 s for eliminated nested ModLauncher transformation work.
Actual end-to-end savings depend on duplicate side-load rate and critical-path overlap. If the hit rate is low, the
experiment should be rejected rather than expanded.

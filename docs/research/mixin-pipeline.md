# Mixin / ModLauncher transformation research history

This file records BootOptim's startup work around Sponge Mixin, ModLauncher side-loads, ClassInfo metadata resolution and post-Mixin ASM writing. It exists so future agents do not conflate these layers or repeat low-leverage cache experiments.

## Current interpretation — 2026-08-31

The transformation path must be treated as separate layers:

1. Mixin global preparation (`mixin.prepare`), including the first large preparation trigger.
2. Mixin apply work inside `MixinTransformationHandler.processClassWithFlags`.
3. ModLauncher ASM writing after Mixin returns, including `ClassNode.accept(TransformerClassWriter)` and `ClassWriter.toByteArray()`.

Earlier timing of Mixin's callback did **not** include layer 3. PR #48 exists specifically to measure that external writer tail.

## PR #41 / #42 — Mixin callback profiling

**Status: PROFILED**

Exact reference-pack evidence established:

- `processClassWithFlags(AFTER)`: about 10.7 s total
- rewrite / compute-frame-return transformations: 1,197 calls, about 6.56 s inside Mixin
- normal no-rewrite calls excluding the initial dummy target: under about 0.8 s
- `org.sinytra.connector.mod.DummyTarget`: about 3.2–3.5 s, corresponding to the first global Mixin preparation trigger
- Mixin internal profiler: `mixin.prepare` about 3.18 s and `mixin.apply` about 6.36 s

Important correction: the ~6.56 s rewrite timing ends when Mixin returns its transformation result/flags. ModLauncher subsequently writes the `ClassNode` with ASM, so frame computation/serialization cost is outside that number.

## PR #43 — generic Mixin side-load cache

**Status: REJECTED**

The experiment memoized ModLauncher class-byte side-loads used while Mixin resolves metadata.

Exact-pack result:

- calls: 10,120
- hits: 375
- misses: 9,745
- hit rate: 3.71%
- retained cache: about 55.13 MiB
- data served by hits: about 1.45 MiB
- estimated time saved by hits: only about 41.7 ms

Conclusion: broad side-load byte caching is not a meaningful startup lever for this pack and introduces memory/lifetime/semantic complexity. Do not repeat it merely because Mixin performs many side-loads.

## PR #46 — broken ClassInfo negative cache

**Status: REJECTED AS AN OPTIMIZATION; BUG CONFIRMED**

Mixin 0.8.7 stores failed `ClassInfo.forName` resolutions as `null`, but the lookup path distinguishes neither an absent key nor a present-null negative entry when it uses `get(name) == null`. A later Fabric Mixin change uses `containsKey`, confirming the bug pattern.

PR #46 measured the real `ClassInfo.cache` directly without applying the fix. It preserved the broken behavior and counted the exact `containsKey == true && get == null` retry path.

Exact reference-pack warm result on 2026-08-31:

- `for_name_calls=252274`
- `positive_cache_hits=246234`
- `first_absent_resolutions=6033`
- `negative_cached_gets=7`
- `negative_unique=51`
- `negative_retries=7`
- `negative_retry_ms=4.722`
- `negative_still_negative_retries=7`
- `negative_recoveries=0`

The secondary ModLauncher side-load cross-check saw 59 class-not-found results and only 7 repeated missing-class loads, with about 0.133 ms of repeated delegate time.

The direct diagnostic itself incurred about `classification_probe_ms=1072.434`; this is probe overhead and is **not** a production cost or a benefit estimate.

### Decision

Do **not** backport the negative-cache fix as a BootOptim startup optimization for this pack. The bug is real and this run observed zero negative recoveries, but its measured avoidable work is only about **4.7 ms**. That is far below the project's significance threshold and below the maintenance/compatibility cost of patching Mixin internals.

The zero-recovery result is useful semantic evidence but does not change the performance decision.

### Reopen only if

- a different Mixin version/modpack produces orders of magnitude more negative retries or materially larger `negative_retry_ms`; or
- BootOptim needs the fix for correctness independently of startup performance.

Do not reopen based only on the existence of the upstream bug.

## PR #48 — ModLauncher ASM write tail

**Status: ACTIVE DIAGNOSTIC**

PR #48 instruments the exact post-Mixin calls to `ClassNode.accept(ClassWriter)` and `ClassWriter.toByteArray()` using a standalone javaagent. It groups rewritten classes by final flags and reports total/tops for accept, serialization, bytes and classes.

This experiment is intentionally separate from #46 so ClassInfo probe overhead cannot contaminate fine writer timings. A real-pack measurement is still required.

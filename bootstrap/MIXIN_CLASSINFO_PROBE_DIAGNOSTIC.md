# Mixin ClassInfo diagnostic experiment

This branch is diagnostic only and must not be merged without real-pack evidence.

## Why this experiment exists

The real pack measured roughly 3.18 s in `mixin.prepare` and 6.36 s in `mixin.apply`. PR #43 showed that a generic cache at Mixin's ModLauncher `ITransformerLoader` boundary has little reuse: 10,120 calls, 375 hits, 9,745 misses and 9,686 unique classes.

Source inspection found a narrower problem inside Fabric Mixin `0.15.2+mixin.0.8.7`: `ClassInfo.forName` writes `null` to its metadata cache after a failed resolution, but tests `cache.get(name) == null` to decide whether to load. Therefore an intended negative cache entry is treated as a miss on every subsequent lookup.

Fabric later fixed this exactly in commit `2c6ea1834edc24b9107fad5b2408493515d81c27` (`Fix: Use null as a proper ClassInfo cache value.`). The immediately preceding commit `292070a3789da55caa2e8842a220f36fb6941464` also avoids many unnecessary `ClassInfo.forName` calls when Mixin only needs to know whether an owner is itself a mixin.

Upstream references:

- https://github.com/FabricMC/Mixin/commit/2c6ea1834edc24b9107fad5b2408493515d81c27
- https://github.com/FabricMC/Mixin/commit/292070a3789da55caa2e8842a220f36fb6941464
- https://github.com/FabricMC/Mixin/commit/1d7fff6e64be9c59c70e95292de6ea3c56f26ac5

## What is instrumented

The already-initialized Mixin ModLauncher plugin is replaced with a transparent delegate before `initializeLaunch`. Only the supplied `ITransformerLoader` is wrapped.

For every side-load the probe records:

- canonical class name;
- success versus `ClassNotFoundException`;
- delegate time;
- whether the name was requested previously.

At JVM shutdown it reads the real `org.spongepowered.asm.mixin.transformer.ClassInfo.cache` without modifying it, selects entries whose value is `null`, and correlates those names with observed side-loads.

Primary output marker:

`BOOTOPTIM_MIXIN_CLASSINFO_PROBE`

The most important line is:

`classinfo_cache=null_entries ... matched_repeated_calls=... matched_est_repeated_ms=...`

`matched_est_repeated_ms` is an estimate of side-load delegate time after the first observed request for names which are still `null` in `ClassInfo.cache`. It is a much narrower ceiling for the broken negative-cache path than generic side-load reuse, but it is still an observational correlation rather than a direct timer inside `ClassInfo.forName`.

## Compatibility invariant of this branch

The probe does **not** cache any class, suppress any callback, alter a `ClassNode`, clone returned bytes, or translate exceptions. Every loader request reaches ModLauncher's original `ITransformerLoader`; the exact returned `byte[]` reference is returned and the exact `ClassNotFoundException` instance is rethrown.

The `ClassInfo.cache` snapshot is read only at shutdown and is never replaced or mutated.

## Decision rule

If `matched_est_repeated_ms` is material relative to `mixin.prepare`/`mixin.apply`, the next experiment should backport the exact intended negative-cache semantics from Fabric commit `2c6ea183` and separately test the `292070a` lookup-elimination changes.

If the correlated repeated cost is small, do not pursue the negative-cache fix as a startup optimization; continue with per-stage apply instrumentation (target member lookup, injector target scans, instruction scans, locals analysis and frame writing).

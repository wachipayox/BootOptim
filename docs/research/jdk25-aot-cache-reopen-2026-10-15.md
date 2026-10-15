# JDK 25 AOT cache — reopened for the mod-loading phase — 2026-10-15

Status: **ACTIVE, mechanism stage.** Not a rejection and not an integration.
The 2026-09-25 NO-GO ([`archive/research/jdk25-aot-cache-laptop-2026-09-25.md`](../archive/research/jdk25-aot-cache-laptop-2026-09-25.md))
was measured on a laptop with a failing disk, with n=1 and a fixed order. Its
JDK-selection premise no longer holds, and the mechanism it tested (class
loading only) is not the full mechanism (JEP 515 method profiles too).

## Why it reopens

1. **Launcher JDK.** The criterion was "if the launcher moves to JDK 25+".
   Pandora #103 runs the `profile_wachiland-*` profiles on Java 25
   (`java-runtime-epsilon`, 25.0.1) on PC, with fallback to 21.
2. **Method profiles.** JEP 515 stores JIT method profiles in the cache. The
   September entry tested only class loading and linking (JEP 483).
3. **Evidence quality.** The September numbers came from the laptop with the
   failing disk. The fast PC on JDK 25 has not been measured with the cache.

Phase in scope: JVM start to `mod_entrypoint` and `main_menu`. The fast-PC
report shows about 46.5 s of the 53.6 s to the menu after JVM start on JDK 25
(see [`jit-compiler-pressure-2026-10-14.md`](jit-compiler-pressure-2026-10-14.md)).

## Mechanism finding 1: custom loaders are not a hard barrier

ModLauncher's `TransformingClassLoader` extends `cpw.mods.cl.ModuleClassLoader`,
which extends `java.lang.ClassLoader` and not `URLClassLoader` (checked with
`javap` on `modlauncher-11.0.5.jar` and `securejarhandler-3.0.8.jar` from the
launcher's library folder). Before this check, the working assumption was that
classes from such loaders are never cacheable. A toy test on JDK 25.0.1 (scratch
app, 300 classes per case, no BootOptim code) disproves that assumption:

| Case | Classes served from the cache |
|---|---|
| App-loader classes from a directory (`-cp classes/`) | 0 of 300 (not archived) |
| App-loader classes from a jar | 300 of 300 |
| `URLClassLoader` subclass, jar | 300 of 300 |
| Plain `ClassLoader` subclass that rewrites bytes and calls `defineClass` (ModLauncher-like), same rewrite in training and in the run | 300 of 300 |
| Same loader, **different** rewrite (same length) than in training | 0 of 300; the JVM parsed the new bytes and returned the new value |
| Same loader, no rewrite | 0 of 300; the original value was returned |

The JVM compares the defined bytes with the training bytes and parses them
again when they differ. No stale class was served in any case. So transformed
mod classes can be cached **if** the transformer output is byte-identical
between training and consumption. That is **not measured yet** on this pack.

## Open questions (need a real game run)

1. How many classes loaded before `mod_entrypoint` (27,944 in the fast-PC run
   on Java 25) and before `main_menu` are served from the cache, when training
   and consumption use the same pack revision, JDK build, JVM flags and
   classpath?
2. Are the transformed bytes deterministic across launches? A mismatch is
   silent (the class is parsed again), so the hit rate is the measurement.
3. Does JEP 515 add JIT benefit (profiles for JDK and cached methods)?
4. Cache size, training time, and invalidation: the cache is tied to the JDK
   build, the classpath and the pack revision.

## Plan

1. One training run with `-XX:AOTCacheOutput=<file>` (not a measurement), then
   one consuming run with `-XX:AOTCache=<file>` and
   `-Xlog:class+load=info,aot=info`. Count the sources of class loads up to
   `mod_entrypoint` and `main_menu`.
2. Only if the hit rate is material: ABBA ×2 with `--no-update` on a fixed
   revision, comparing medians of `mod_entrypoint`, `main_menu` and the first
   `[rrls/]: Creating new resource manager!` line.
3. Otherwise record the rejection, with the hit rate as the reopening evidence.

## Reopening criteria (if rejected)

A low hit rate for mod classes on this pack that stays low after a deterministic
training run, or a balanced physical A/B that shows no repeatable Java-to-menu
gain after cache loading and launcher overhead.

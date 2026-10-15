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

## Determinism review (code and bytecode, no game run)

- **Most classes are not rewritten.** Only classes that a launch plugin
  changes reach `defineClass` with different bytes. The 2026-09-04 exact-pack
  analysis counted 1,199 rewritten classes
  ([`archive/research/fml-modlauncher-lifecycle-critical-path-2026-09-04.md`](archive/research/fml-modlauncher-lifecycle-critical-path-2026-09-04.md),
  PR #48). The other classes get the jar bytes unchanged, so they match the
  training bytes by construction. The fast-PC run loaded 27,944 classes before
  `mod_entrypoint`, a different population, so the ratio is indicative only.
- **ModLauncher 11.0.5** (`javap -c` over all 78 classes): no
  `identityHashCode`, no `nanoTime`/`currentTimeMillis`, no `Random`. It has 12
  `HashMap`/`HashSet` sites. Their iteration order is stable across launches
  unless the key is an identity-hashed object such as `Class`. Not checked per
  site.
- **securejarhandler 3.0.8**: same scan, no identity hash or clock calls; 39
  `HashMap`/`HashSet` sites, same caveat.
- **Mixin** is not in the launcher library folder and was not scanned.
- **Connector** has a transformed-JAR cache with 3/3 hits on warm hosted runs
  ([`archive/research/connector-warm-residual-2026-09-10.md`](archive/research/connector-warm-residual-2026-09-10.md)),
  which suggests its output is stable across launches.

Conclusion: the ceiling is set by the rewritten subset and by any
non-deterministic step in Mixin, which this review cannot exclude. The
measurement itself settles it: `tools/fast-pc-bench/aot_class_sources.ps1
-TrainingLog` reports `MISMATCH` for classes that the training run loaded but
whose bytes differ at consumption.

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

## Plan (run only when the PC is free and the user has said so)

Pre-checks: no `WachilandLauncher*` and no game `javaw` running
(`run_once.ps1` refuses otherwise); the instance `info_v1.json` restored after
each step; a fixed Beta revision (`PACK.md`) for the whole series.

Run count: 1 training + 1 hit run + 1 primer + ABBA ×2 (8 measured runs) =
11 launches, about 20 minutes.

1. **Training (not a measurement).** Flags: `-XX:AOTCacheOutput=C:\BootOptimBench\local\aot\<rev>.aot`
   `-Xlog:class+load=info:file=C:\BootOptimBench\local\aot\train-classload.log:uptime`, plus the
   same `-XX:ActiveProcessorCount=8` and affinity as the candidate. Stop at
   `main_menu` through `exitOnTitle`, and check that the JVM prints
   `AOTCache creation is complete` before it exits.
2. **Hit run (not a measurement).** Same flags with `-XX:AOTCache=<same file>`
   and `-Xlog:class+load=info:file=C:\BootOptimBench\local\aot\hit-classload.log:uptime`
   (one fixed file per tag, because `run_once.ps1` creates its run folder
   internally). Then run `tools\fast-pc-bench\aot_class_sources.ps1 -ClassLoadLog
   hit-classload.log -TrainingLog train-classload.log -Report <run>\bootoptim-startup.log`.
   Record the table at `mod_entrypoint` and `main_menu`. Stop here if
   `AOT/CDS` plus `MISMATCH` is small and the mismatch count is high.
3. **ABBA ×2** with `run_once.ps1`: `A` = control (Java 25 default, no
   `-XX:AOTCache`), `B` = candidate (same plus `-XX:AOTCache=<file>`); one
   discarded primer of `A` first; then `A B B A A B B A`. Compare the medians
   of `mod_entrypoint`, `main_menu` and the first `[rrls/]: Creating new resource manager!`
   line, and check that every candidate falls outside the control range.
4. **Record** the cache size, training time, and that the cache is invalid for
   a different JDK build, classpath or pack revision.

## Reopening criteria (if rejected)

A low hit rate for mod classes on this pack that stays low after a deterministic
training run, or a balanced physical A/B that shows no repeatable Java-to-menu
gain after cache loading and launcher overhead.

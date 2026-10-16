# JDK 25 AOT cache — reopened for the mod-loading phase — 2026-10-15

Status: **research closed on the fast PC; not integrated.** The ABBA shows
about −9.25 s to `main_menu` with a cache trained on the same pack revision and
the same BootOptim JAR. No runtime code is proposed here. Pandora design and
implementation go to a separate session. **Pending validation:** whether the
gain holds after a pack revision change, by training again on the new revision.

The 2026-09-25 NO-GO ([`archive/research/jdk25-aot-cache-laptop-2026-09-25.md`](../archive/research/jdk25-aot-cache-laptop-2026-09-25.md))
used a laptop with a failing disk, n=1 and a fixed order. It tested class
loading only (JEP 483), not JIT method profiles (JEP 515).

## Why it reopened

1. **Launcher JDK.** The criterion was "if the launcher moves to JDK 25+".
   Pandora #103 runs the `profile_wachiland-*` profiles on Java 25
   (`java-runtime-epsilon`, 25.0.1) on PC, with fallback to 21.
2. **Method profiles.** JEP 515 stores JIT profiles in the cache.
3. **Evidence quality.** The September numbers came from the laptop with the
   failing disk. The fast PC on JDK 25 had not been measured with a cache.

## Mechanism

**Custom loaders are not a hard barrier.** ModLauncher's `TransformingClassLoader`
extends `cpw.mods.cl.ModuleClassLoader`, which extends `java.lang.ClassLoader`,
not `URLClassLoader` (checked with `javap` on `modlauncher-11.0.5.jar` and
`securejarhandler-3.0.8.jar`). A toy test on JDK 25.0.1 (300 classes per case,
no BootOptim code):

| Case | Classes served from the cache |
|---|---|
| App-loader classes from a directory | 0 of 300 (not archived) |
| App-loader classes from a jar | 300 of 300 |
| `URLClassLoader` subclass, jar | 300 of 300 |
| Plain `ClassLoader` that rewrites bytes and calls `defineClass`, same rewrite as training | 300 of 300 |
| Same loader, different rewrite (same length) | 0 of 300; the JVM parsed the new bytes |
| Same loader, no rewrite | 0 of 300; the original value was returned |

The JVM compares the defined bytes with the training bytes and parses them
again when they differ. No stale class was served in any case.

**Determinism review (code and bytecode):**

- Most classes are not rewritten: they reach `defineClass` with the jar bytes,
  so they match training by construction. The 2026-09-04 exact-pack analysis
  counted 1,199 rewritten classes ([`archive/research/fml-modlauncher-lifecycle-critical-path-2026-09-04.md`](archive/research/fml-modlauncher-lifecycle-critical-path-2026-09-04.md), PR #48).
- ModLauncher 11.0.5 (`javap -c`, 78 classes): no `identityHashCode`, clock or
  `Random` calls; 12 `HashMap`/`HashSet` sites, unchecked per site.
- securejarhandler 3.0.8: same scan, no identity hash or clock; 39 sites.
- Mixin is not in the launcher library folder and was not scanned.

## Measurement (fast PC, 2026-10-16)

Origin: fast PC through Wachiland Launcher #103, Java 25.0.1
(`java-runtime-epsilon`), full mask `FFFF`, no `ActiveProcessorCount`,
`--no-update`, Beta `rev_7d5b9e504f8422494860364f9312da8e`, BootOptim JAR
`bootoptim-bench-6b7d84cf.jar` (the same for training and every run). Endpoints
from `bootoptim-startup.log`: `mod_entrypoint` and `main_menu`.

**Training (not a measurement).** One run with
`-XX:AOTCacheOutput=C:/BootOptimBench/local/aot/b06-rev7d5b.aot`. The cache is
**304 MB**; the run took about **2 min** including the cache dump at exit.

**Hit check (not a measurement).** One run with `-XX:AOTCache` and class+load
logs. Counted with `tools/fast-pc-bench/aot_class_sources.ps1` (classes loaded
up to each marker):

| Class source | At `mod_entrypoint` | At `main_menu` |
|---|---:|---:|
| Loaded in total | 27,729 | 65,304 |
| Non-JDK, served from the cache (`AOT/CDS`) | **9,869** | 22,096 |
| JDK, from the base archive (`JDK-CDS`) | 5,443 | 5,475 |
| Defined by a custom loader, not in training (`DEFINED`) | 1,933 | 3,434 |
| In training but not served now (`MISMATCH`) | 2,011 | 5,262 |
| Lambda proxies (run-specific names) (`LAMBDA`) | 8,455 | 28,989 |
| Other (jar/union, `JRT`, unknown) | 18 | 48 |

The `MISMATCH` count does not separate causes (changed bytes, a different
loader, or the class failing in this run). Lambda proxies can never match by
name.

**ABBA ×2.** Primer of control discarded, then `A B B A A B B A`. A = control,
B = `-XX:AOTCache=C:/BootOptimBench/local/aot/b06-rev7d5b.aot`.

| Arm | n | `mod_entrypoint` median | `main_menu` median | `main_menu` range |
|---|---:|---:|---:|---:|
| control | 4 | 37.42 s | 58.74 s | 56.63–59.91 s |
| cache | 4 | 32.09 s | 49.50 s | 48.72–49.73 s |

Cache − control: **−9.25 s to `main_menu` (−15.7 %)**, −5.33 s to
`mod_entrypoint`. The ranges do not overlap. The first control attempt crashed
(see "Other findings") and was repeated; it is not in the table.

**Limits.** One cache from one revision and one JAR. Not measured after a
revision change, a JDK change or a JAR change; each invalidates the cache by
design. Training cost is not in the medians. The measurement is JVM uptime, not
launcher Start, so Pandora's own overhead is not included.

## Other findings

- **Pandora drops backslashes in `jvm_flags`.** Pandora splits the flags with
  shell-word rules, so `C:\BootOptimBench\...` arrives as `C:BootOptimBench...`.
  That broke `-Xlog` and wrote the cache into the game folder. Use
  `C:/BootOptimBench/...` in any JVM flag path. This is a launcher issue, found
  here, to be recorded in the Pandora docs.
- **Mod-construction crash (pack, not the cache).** A control run crashed in
  parallel mod construction: Registrate's `OneTimeEventReceiver.addModListener`
  threw an NPE from a shared `Table`, and Sodium aborted. It happened once in 12
  startups of the same revision and JAR. Reported as WachilandEliteCompanion#6.
- **Counter tool.** `aot_class_sources.ps1` now accepts uptime-only log lines,
  maps `union:` and `__JVM_LookupDefineClass__` sources, and separates lambda
  proxies.

## Requirements for a Pandora design (not implemented here)

1. Training runs off the play path (background after a normal start, never
   blocking the player).
2. One cache per instance, per JDK build and per profile revision.
3. Invalidate on any change of those three.
4. Fallback to a start without the cache when training or loading fails, with
   a `[WBOMBO-COMPAT]` line (FALLBACK, and ACTIVE when the cache is used).

## Pending validation

- The gain after a pack revision change: train again on the new revision and
  repeat the ABBA. Without this, the −9.25 s applies only to rev `7d5b9e50…`.

## Reopening criteria (if the revision-change check fails)

If the cache gives no repeatable gain on a new revision, or the revision-change
retraining gives a hit rate near zero, close this entry as a rejection with
those numbers.

# Deterministic Mixin names for the JDK 25 AOT cache — 2026-11-10

**Status: candidate, measured on the fast PC only.** Implemented as a second transformer of the zipfs read-only agent
(`tools/zipfs-readonly-agent`), which Pandora already ships on Java 25. Not embedded in Pandora yet: that needs this
change merged first (the embedded JAR records its source commit).

## Problem

The JDK 25 AOT cache serves a class only if the bytes defined at run time equal the bytes seen in training. Mixin made
its output different on every launch in two places (both `UUID.randomUUID()`):

- `MixinProcessor.<init>` — the session id, whose first six hex digits prefix the merged synthetic methods (`md<6 hex>$…`).
- `InnerClassGenerator.getUniqueReference(String, ClassInfo)` — the `$Anonymous$<uuid>` names of generated inner classes.

Every class Mixin rewrites with one of those names was therefore never served from the cache, nor were its subclasses
(`Block`, `BlockEntity`, `Entity`, … are rewritten, so everything below them in the hierarchy was excluded too).
Baseline from the profiling run of 2026-11-10: 21,702 classes served, 5,069 real non-JDK classes not served.

## Change

Replace the two calls, and only those, by deterministic ones:

| Site | Replacement | Why it is safe |
|---|---|---|
| `MixinProcessor.<init>` | fixed name-based UUID | one processor merges into a given class, so the prefix cannot collide |
| `getUniqueReference` | `UUID.nameUUIDFromBytes(innerName + "|" + target)` | unique per (inner class, target class), the pair Mixin asks to be unique; same on every launch |

Compatibility ([WBOMBO-COMPAT] rule, no version checks): the transform is applied only when the method contains exactly
one `UUID.randomUUID()` call (`MixinDeterminismTransformer.patch`). Otherwise the class is left untouched and
`[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=mixin-deterministic-names …` is written; on success
`ACTIVE …` for each class. The JVM compares the bytes before using a cached class, so a stale cache cannot serve a
different class. Switch: `-Dwbombo.mixinDeterministicNames=off`. The transformer removes itself once both classes were
seen, or after 120 s if Mixin never loads. Self-test: `MixinPatchSelfTest` (stable name, per-inner-class difference,
rejection of 0 and 2 calls).

**Placement.** In the zipfs agent (Pandora's) and not in the BootOptim bootstrap: the Mixin library lives in the
service layer, outside the ModLauncher transformers the bootstrap can reach, so only a `java.lang.instrument` hook can
rewrite it, and Pandora already ships such an agent only for Java ≥ 25 (the only JDK with this cache). The same JAR
also keeps the AOT cache key logic in one place.

## Measurement

Origin: fast PC (i5-12600KF, 32 GB, RTX 3070), Wachiland Launcher, Java 25.0.1 (`java-runtime-epsilon`), instance
«1.21.1» (`rev local`, 251 mods), the cache trained once per variant and used in every measured run (`mode=use`,
checked in `launcher.log` for each run). Start: JVM start. End: `main_menu` (BootOptim `PHASE`) and RRLS
`state=HIDE`. Control `C` and candidate `M` differ only by `-javaagent:` with the Mixin transformer added to `jvm_flags`
(a separate JAR built from the same classes; the integrated agent was not the one measured). Both use the same
FancyMenu build, so the skin-cache change does not mix in.

**Gate (classes served, `-Xlog:class+load` until `main_menu`, one run each):**

| | served from AOT | non-JDK classes loaded from JARs |
|---|---|---|
| control | 22,060 (21,741 non-JDK) | 7,107 |
| candidate | 25,589 (25,259 non-JDK) | 2,460 |

The gate passes: +3,518 non-JDK classes served. (Before `main_menu` of each run; logging slows both.)

**A/B, alternating M C M C M C M C (4 + 4, seconds):**

| | control | candidate |
|---|---|---|
| `main_menu` | 40.85, 40.77, 41.18, 40.58 (mean 40.85) | 39.40, 39.54, 39.34, 39.61 (mean 39.47) |
| menu visible (`HIDE`) | 43.86, 43.73, 44.13, 43.73 (mean 43.86) | 42.21, 42.49, 42.23, 42.54 (mean 42.37) |
| `mod_entrypoint` | 25.19, 24.70, 25.17, 24.51 | 23.73, 23.71, 23.72, 23.89 |

−1.4 s to `main_menu`, −1.5 s to the visible menu; the ranges do not overlap. Wall-clock times of the startup, not
CPU time.

**The anomalous series (`C M M C M C C M`) was checked.** The AOT-key hypothesis is ruled out: all eight runs have
`mode=use` and a stable key per variant (`launcher.log`). The first control run (50 s) was a cold start; the rest is
spread. It was not a cache problem and no other cause was found.

**New series, 6 + 6, order `C M M C C M M C C M M C`** (after a trained warm-up per variant; every JAR copied with
`cp -p`; all 12 runs `mode=use` with the same keys; AnalogAudio welcome screen off in both; same FancyMenu build). The
BootOptim `PHASE` log was not produced in this series, so the ends are RRLS `state=WAIT` (title screen ready) and
`state=HIDE`, from the JVM start. `HIDE` includes the Mojang skin lookup (no skin cache in this build), hence the
larger spread.

| | control (6) | candidate (6) |
|---|---|---|
| `WAIT` mean / median | 46.6 / 45.3 s | 45.5 / 44.4 s |
| `HIDE` mean / median | 49.5 / 49.5 s | 48.4 / 47.3 s |
| `HIDE` range | 44.6–53.9 | 45.8–53.7 |

Candidate −1.1 s (mean) and −0.9 s (median) to `WAIT`; −1.1 s / −2.2 s to `HIDE`. Spread is large (sd ≈ 3 s, three
slow runs spread over both variants) and the ranges overlap, so this series alone does not prove the gain; it does
show no regression, and its direction agrees with the 4 + 4 series (−1.4 s). The best estimate stays around −1 s
(−0.5 to −1.5 s expected). Three series in total: two favorable or neutral, one (`C M M C M C C M`) unfavorable and
unexplained.

**Series 4 + 4 with the startup log** (control = BootOptim `8e916a87`, FancyMenu skin cache #8 in both variants, order
`C M M C C M M C`, trained cache per variant, all `mode=use`, welcome screen off; `main_menu` = BootOptim `PHASE`):

| | control | candidate |
|---|---|---|
| `main_menu` | 40.17, 37.49, 38.60, 43.80 (mean 40.0) | 36.40, 37.05, 42.88, 41.14 (mean 39.4) |
| `mod_entrypoint` mean | 28.8 s | 28.1 s |

Candidate −0.65 s at `main_menu`, same sign in the other marks, but the ranges overlap (runs drift ~+5 s late in the
series). Across the three series the estimate is −0.6 to −1.4 s: small, never significantly negative except in the
unexplained `C M M C M C C M` series.

**Safety checked on every error path** (self-test): the transform hook never throws (garbage, null or class without the
expected call: left untouched, FALLBACK logged); a `MixinNames` not reachable from the boot class path rejects the patch;
a failing log or `removeTransformer` cannot change the outcome. `anonymousUuid` falls back to `UUID.randomUUID()` (the original
behaviour, one `[WBOMBO-COMPAT] FALLBACK … reason=duplicate_key|unstable_target` line) if the key was already used in this JVM
(a repeated name would be a `LinkageError`), if the target text carries an identity hash, or if anything fails. The target
Mixin passes is `ClassInfo`, whose `toString()` is its class name (checked with `javap`): stable. Mixin loading cannot be broken by the hook.

## Reopen / next steps

- Embed the rebuilt agent in Pandora (update `zipfs-readonly-agent.jar.source` commit + sha256) after this is merged.
- Optionally repeat the A/B on the laptop (Java 25 + AOT).
- Check with the hosted exact-pack CI that the hook does not change behavior when Mixin loads late.

# Mod-loading subphases: fast PC vs hosted CI — 2026-10-15

Status: **PROFILED** — the fast-PC split is measured (2026-10-16, three runs). The CI column is pending the smoke of PR [#365](https://github.com/wachipayox/BootOptim/pull/365) at `9e17e3d2`. No optimization is proposed, promoted or rejected here.

## Premise and scope

"Mod loading" = JVM start → first resource reload. RRLS hides the reload itself, so the remaining gain is in this segment. Until now only `mod_entrypoint` and `main_menu` were marked, and the September work on this segment ran on hosted CI or on the laptop with a failing disk. Nothing had been split by subphase on Windows. That is the new premise.

## Tooling (PR #365)

`StartupDiagnostics.phase` (bootstrap) and `StartupProfiler.markPhase` (game layer) write `PHASE name=… uptime_ms=…` plus `JVM phase=…` counters. Zero cost unless `boot_optim.startupLog.enabled` is set.

| Marker | Written from | Status |
|---|---|---|
| `modlauncher_start` | `EarlyStartupProbeService.initialize` | works; after ModLauncher's service construction |
| `root_mod_discovery_end` / `dependency_discovery_end` | discovery locators | works |
| `bootstrap_start` / `bootstrap_end` | mixin, `Bootstrap.bootStrap()` HEAD/RETURN | works; proxy for "Mixin ready" (no clean hook) |
| `mod_entrypoint`, `main_menu` | existing | works |
| `register_<registry>` | `RegisterEvent`, LOWEST priority, one per dispatch | works; timeline inside the registration phase |
| `common_setup_start` | `FMLCommonSetupEvent` | works, but it fires **after** the first reload has started, so it is not a registration boundary |
| `reload_start` | mixin on `reloadResourcePacks(boolean, GameLoadCookie)` HEAD | **does not fire**: RRLS takes over the first reload before the hook runs. Use the `[rrls/]: Creating new resource manager!` line, see below |

## Fast-PC measurements (Beta rev 3, Java 25.0.1 Microsoft, launcher 103, NVMe, Windows 11)

Runs (`tools/fast-pc-bench/run_once.ps1`, instance restored afterwards):

| Run | Build | `mod_entrypoint` | `main_menu` | Use |
|---|---|---|---|---|
| `pr365-markers` | first build (`reload_start` on the wrong method) | 37.6 s | 59.8 s | markers |
| `pr365-markers2` | `9e17e3d2` | 36.9 s | 58.9 s | **segment numbers below** |
| `pr365-jfr` | `9e17e3d2` + `settings=profile` | 35.6 s | 57.0 s | attribution only |

Run-to-run spread is about 3 s on `main_menu`, so single segments below are ±1 s at best.

Segments from `pr365-markers2` (uptime s, PC):

| Segment | Start → end | Duration | Notes |
|---|---|---|---|
| JVM → ModLauncher services | 0 → 10.0 | ~10.0 | the marker sits in our service `initialize`, so it is later than the earlier 4.5 s estimate from the first log line; not split further |
| root discovery | 10.0 → 13.1 | 3.1 | |
| dependency discovery (JarJar) | 13.1 → 19.5 | 6.4 | discovery total 9.5 s |
| module layer, AT, config, Mixin prep | 19.5 → 29.1 | 9.6 | no marker inside; next step |
| vanilla Bootstrap | 29.1 → 33.7 | 4.6 | `Vanilla bootstrap took 4595 milliseconds` |
| window, render thread → `mod_entrypoint` | 33.7 → 36.9 | 3.2 | |
| registration (`RegisterEvent` dispatches) | 40.5 → 51.2 | ~10.7 | block dispatch alone: 40.7 → 49.5 (**8.8 s**) |
| reload start (derived, not a marker) | ~52 | — | log `Creating new resource manager!` 18.3 s after the `Vanilla bootstrap took` line; ±0.5 s |
| `common_setup_start` | 55.4 | — | after the reload started |
| `main_menu` | 58.9 | — | |

CI reference (Linux, 4 CPU, JDK 25.0.4, rev 3, [baseline](beta-rev3-baseline-2026-10-15.md)): `mod_entrypoint` 20.4 s (R1) and 26.6 s (R2 with JFR). PC: 36.9 s. The CI split per marker is what the pending smoke will give.

## JFR attribution (run `pr365-jfr`)

**Block dispatch (40.5–49.5 s) is CPU-bound, not I/O-bound.** 745 execution samples in that window; 579 on `modloading-sync-worker`. Top frames:

- `BitSetDiscreteVoxelShape.join` (lambda) — 187 samples
- Lithium `LithiumDoublePairList.forMergedIndexes` / `merge` — 117 samples
- `DiscreteCubeMerger.forMergedIndexes` — 19 samples

Voxel-shape joins are roughly 45 % of the window's samples. This is the "static shape joins for wall, cross and pipe block states" frontier already listed as frontier 2 of [#360](https://github.com/wachipayox/BootOptim/pull/360), which was never opened. It now has a PC number: about 8.8 s of block dispatch, of which the joins are a large share. It is not yet proven to be Windows-specific: on CI the same joins should appear, and the CI register timeline will show whether their share is similar.

**Decocraft JAR reads are not the driver.** 175 reads of `mods\Decocraft -57-…jar` happen at 38–52 s, but they form a burst of about 0.6 s wall time across 15 threads (average 0.31 s per read, ~55 s summed thread time). That burst is inside the block dispatch, but it covers under 10 % of its duration. Its wall-time weight is small. The read-ahead ceiling in [`docs/archive`](../archive/README.md) is therefore not reopened by this run.

**Discovery and JarJar I/O is small.** Reads before the dependency end total under 3 s thread-time. The wrapper-lookup fallback (`DiscoveryStartLocator.locateWrapper`) did not show up in the top reads. The Windows-specific question for discovery (Defender scanning JARs) cannot be answered from JFR: it does not record Defender.

**JVM counters are not mutually consistent.** At `mod_entrypoint`, `jit_ms` (57 s) exceeds `process_cpu_ms` (31 s) even though compiler threads are part of the process CPU. Treat JIT as an overlapping, multi-thread total, not as a critical-path share.

## Subphase verdict

| Subphase | PC wall | Windows-specific? | Front / why none |
|---|---|---|---|
| JVM → ModLauncher services | 10.0 s | unknown | not split; needs JFR class-load events before any claim |
| Discovery + JarJar | 9.5 s | not shown; I/O small in JFR | wrapper fallback F1 not seen in reads. Measure it on CI before any change. Generic JarJar cache rejected (#134); copy+SHA sidecar rejected for FML 4.0.43 |
| Module layer, AT, config, Mixin prep | 9.6 s | unknown | **no front yet**: per-config Mixin prepare NO-GO, AT loop tiny. Needs a marker inside (next step) |
| Vanilla Bootstrap | 4.6 s | no (CPU) | double transform explained, not a duplicate |
| Window → `mod_entrypoint` | 3.2 s | unknown | small |
| Block registration dispatch | 8.8 s | no evidence of I/O; CPU in voxel-shape joins | **Front F2**: static shape joins for wall/cross/pipe states (#360 frontier 2). Needs the CI register timeline to compare shares |
| Other registrations and common setup | ~2 s + ~3 s | no | closed: Registrate callbacks, construction scheduler, ModelBakery allocation |

## Reopening criteria

- **F2 (shape joins):** worth a bounded investigation if the CI register timeline shows block dispatch with a similar share of joins. A PC-only result is not enough to call it Windows-specific.
- **F1 (wrapper fallback):** only if the CI discovery segment is well below the PC one and the JFR reads point at `DiscoveryStartLocator`. They do not, in this run.
- **Module layer (9.6 s):** needs a marker inside the segment first.

## Still to do

1. CI column: read the exact-pack smoke of `9e17e3d2` (`bootoptim-startup.log`, PHASE lines) and fill the register timeline.
2. Optional: a Defender A/B on the JARs needs the user to decide; not done here.

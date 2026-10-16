# Mod-loading subphases: fast PC vs hosted CI — 2026-10-15

Status: **PROFILED** — PC split measured on 2026-10-16 (three runs) and CI split read from the exact-pack smoke of `04dc9b03` (run [38814952148](https://github.com/wachipayox/BootOptim/actions/runs/38814952148), success). The CI block-dispatch number matches the PC one within 0.3 s. No optimization is proposed, promoted or rejected here.

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
| `register_<registry>` | `RegisterEvent`, LOWEST priority, one per dispatch | works; registration timeline (125 marks on CI) |
| `common_setup_start` | `FMLCommonSetupEvent` | works, but it fires **after** the first reload has started, so it is not a registration boundary |
| `reload_start` | mixin on `reloadResourcePacks(boolean, GameLoadCookie)` HEAD | **no effect**: RRLS takes over the first reload before the hook runs. Use the `[rrls/]: Creating new resource manager!` log line instead |

## Segments: PC vs CI (uptime, seconds)

Fast PC: run `pr365-markers2` (`9e17e3d2`). CI: smoke `04dc9b03`, hosted Linux, 4 CPU, JDK 25.0.4, rev 3.

| Segment | PC | CI | Note |
|---|---|---|---|
| JVM → ModLauncher services (`modlauncher_start`) | 10.0 | 3.0 | **largest difference before the entrypoint**; see the JFR section |
| root discovery | 3.1 | 0.6 | |
| dependency discovery (JarJar) | 6.4 | 7.6 | |
| module layer, AT, config, Mixin prep | 9.6 | 11.2 | similar |
| vanilla Bootstrap | 4.6 | 4.5 | similar |
| window → `mod_entrypoint` | 3.2 | 4.8 | |
| `mod_entrypoint` (absolute) | 36.9 | 31.5 | |
| registration (`RegisterEvent` dispatches) | 40.5 → 51.2 (~10.7) | 35.6 → 45.6 (~10.0) | |
| **block dispatch** (`register_mob_effect` → `register_block`) | **8.8** (40.7 → 49.5) | **9.0** (35.7 → 44.7) | matches within 0.3 s |
| `common_setup_start` | 55.4 | 50.0 | |
| `main_menu` | 58.9 | 82.5 | CI spent 32.5 s between common setup and menu (reload, not in this segment) |
| reload start (derived from log) | ≈52 | — | `Creating new resource manager!` 18.3 s after `Vanilla bootstrap took`; ±0.5 s; no CI equivalent |

CI `mod_entrypoint` in the baseline ([beta-rev3-baseline](beta-rev3-baseline-2026-10-15.md)) was 20.4 s. This smoke gave 31.5 s on the same revision, so hosted-runner speed varies by about 10 s between runs. Compare only within one run set.

## Block dispatch: same cost on both sides

The 8.8 s (PC) and 9.0 s (CI) block-registration dispatch is the same CPU-bound work on both machines, so it is **not** Windows-specific. JFR (PC) attributes it to voxel-shape joins: 745 execution samples in the window, 579 on `modloading-sync-worker`, top frames `BitSetDiscreteVoxelShape.join` (187) and Lithium `LithiumDoublePairList.forMergedIndexes`/`merge` (≈117), about 45 % of the window. This is the "static shape joins for wall, cross and pipe block states" frontier, frontier 2 of [#360](https://github.com/wachipayox/BootOptim/pull/360). Frontier 2 was then integrated in `agent/integration-current` (block-shape-or-memo, 2026-10-16), so this measurement describes the pre-integration state on rev 3.

**Decocraft JAR reads are not the driver.** 175 reads of `mods\Decocraft` at 38–52 s form a burst of about 0.6 s wall time across 15 threads. That burst sits inside the block dispatch but is under 10 % of its duration.

## JFR attribution, PC (run `pr365-jfr`)

**The pre-ModLauncher window is Windows-specific.** In 0–10 s on the main thread, 194 native samples are in `WindowsNativeDispatcher.GetVolumePathName0` and 13 in `GetFileSecurity0`. All come from one Java path: `jdk.nio.zipfs.ZipFileSystemProvider.getZipFileSystem` → `ZipFileSystem.<init>` → `Files.isWritable`. That is one ACL and volume check per JAR opened as a ZIP filesystem, paid on Windows and cheap on Linux. CPU samples in the same window are few (about 1 s of Java), so the 10 s is mostly waiting and native calls, not interpreted work.

- Owner: the JDK zipfs path, called from ModLauncher/FML while it opens mod JARs. BootOptim's code does not open ZIP filesystems (`grep` finds no `FileSystems`/`newFileSystem` in `src/main` or `bootstrap/src/main`).
- So this is **outside BootOptim's code**. Not proposed as an optimization here. It is the largest PC–CI difference in the segment (≈7 s) and should be the first item for whoever owns the ModLauncher path.

**Discovery I/O is small.** Reads before the dependency-discovery end total under 3 s thread-time. The wrapper-lookup fallback (`DiscoveryStartLocator.locateWrapper`) did not show in the top reads.

**JVM counters are not mutually consistent.** At `mod_entrypoint`, `jit_ms` (57 s) exceeds `process_cpu_ms` (31 s), so JIT is a multi-thread total, not a critical-path share.

## Subphase verdict

| Subphase | PC | CI | Windows-specific? | Front / why none |
|---|---|---|---|---|
| JVM → ModLauncher services | 10.0 s | 3.0 s | **yes, evidence**: `Files.isWritable` / `GetVolumePathName0` per JAR in the JDK zipfs path | owner is outside BootOptim; no proposal here |
| Discovery + JarJar | 9.5 s | 8.2 s | not shown | wrapper fallback not seen in reads; no change proposed |
| Module layer, AT, config, Mixin prep | 9.6 s | 11.2 s | no | **no front**: per-config Mixin prepare NO-GO, AT loop tiny |
| Vanilla Bootstrap | 4.6 s | 4.5 s | no | double transform explained, not a duplicate |
| Window → `mod_entrypoint` | 3.2 s | 4.8 s | no | small |
| Block registration dispatch | 8.8 s | 9.0 s | no (same on CI) | **F2** (shape joins), already integrated in `agent/integration-current` by block-shape-or-memo; the CI number is the baseline for that change |
| Other registrations and common setup | ~2 s + ~3 s | ~1 s + ~5 s | no | closed: Registrate callbacks, construction scheduler, ModelBakery allocation |

## Reopening criteria

- **F2:** closed by integration. Re-measure block dispatch on CI after that change before claiming more.
- **Pre-ModLauncher zipfs cost:** reopen only with a BootOptim-owned path that opens the same JARs, or a ModLauncher/FML change; neither exists today.
- **Module layer (9.6 s PC / 11.2 s CI):** needs a marker inside the segment first.

## Remaining

1. A smoke run on the merge commit `3847728c` (rebased on `agent/integration-current`) to confirm 0 FALLBACK and `main_menu` on the integrated code.
2. A Defender A/B on the JARs would need the user's decision; not done.

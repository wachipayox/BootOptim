# Mod-loading subphases: fast PC vs hosted CI — 2026-10-15

Status: **PROFILED** — tooling delivered; the subphase split on the fast PC is **not measured yet** (waiting for the user's go-ahead to launch the game). No optimization is proposed, promoted or rejected here.

PR: [#365](https://github.com/wachipayox/BootOptim/pull/365) (markers only, exact-pack smoke requested).

## Premise and scope

"Mod loading" here means JVM start → first resource reload (`[rrls/]: Creating new resource manager!`). RRLS hides the reload itself, so the remaining gain is in this segment. Until now only `mod_entrypoint` and `main_menu` were marked. Every September investigation of this segment (see the pre-entrypoint summary in [README](README.md) and [`docs/archive/README.md`](../archive/README.md)) was run on hosted CI or on the laptop with a failing disk, and was never split by subphase on Windows. That is the new premise.

## Tooling delivered

`StartupDiagnostics.phase` (bootstrap) and `StartupProfiler.markPhase` (game layer) write `PHASE name=… uptime_ms=…` plus a `JVM phase=…` counter line to the startup report. They cost nothing unless `boot_optim.startupLog.enabled` is set.

| Marker | Written from | Boundary it closes |
|---|---|---|
| `modlauncher_start` | `EarlyStartupProbeService.initialize` | JVM → ModLauncher services (stdout-only `transformation_service_construct` is earlier, not in the report) |
| `root_mod_discovery_end` | `DiscoveryEndLocator` | root locators done |
| `dependency_discovery_end` | `DependencyDiscoveryEndLocator` | JarJar / dependency discovery done |
| `bootstrap_start` / `bootstrap_end` | mixin on `Bootstrap.bootStrap()` HEAD/RETURN | vanilla Bootstrap. Used as the proxy for "Mixin ready": there is no clean Mixin-ready hook |
| `mod_entrypoint` | existing | `BootOptim` constructor |
| `registration_end` | `FMLCommonSetupEvent` start | approximation of "all RegisterEvent dispatches done" |
| `reload_start` | mixin on `Minecraft.reloadResourcePacks()` HEAD, first call only | start of the first reload |

Both mixins are observation-only with `require = 0`. The `[WBOMBO-COMPAT]` checker covers them automatically.

## Reference numbers (not yet split by marker)

Fast PC, Beta rev 3, Java 25.0.1, run `p103-default-1015-230430`, origin = fast PC, start = JVM start, endpoint = `main_menu` 53.6 s. Times are from the user's log, uptime seconds, rounded:

| Segment (uptime) | PC | Note |
|---|---|---|
| 0 → 4.5 | 4.5 | JVM start → first ModLauncher line (never measured before) |
| 4.5 → 7.5 | 3.0 | ModLauncher services → Sodium graphics probe |
| ~7.5 → ~9 | ~1.5 | unlabelled gap |
| ~9 → 17.5 | ~8.5 | mod discovery + JarJar (`Dependency resolution found`) |
| 17.5 → ~26 | ~8.5 | module layer, AT, config, Mixin preparation |
| ~26 → 31.2 | 4.2 | vanilla Bootstrap (`Vanilla bootstrap took 4180`) |
| 31.2 → 34.25 | 3.0 | render thread, window, start of construction → `mod_entrypoint` |
| 34.25 → 46.5 | 12.25 | construction, RegisterEvent (Create Registrate, Decocraft 3.527 blocks), client setup |
| 46.5 | — | first reload starts |

Hosted CI reference (Linux, 4 CPU, JDK 25.0.4, same revision 3, [baseline](beta-rev3-baseline-2026-10-15.md)): `mod_entrypoint` = 20.4 s (R1) and 26.6 s (R2, with JFR). PC: 34.3 s. The pre-entrypoint gap is about 14 s. The only subphase with a CI number is JarJar, about 1.9 s on CI; on the PC it sits inside the ~8.5 s discovery segment and is not split out. The CI split of the other segments does not exist yet.

## Subphase verdict (what is known, what is not)

| Subphase | PC cost | Windows-specific? | Front / why none |
|---|---|---|---|
| JVM → ModLauncher | 4.5 s | unknown | not ours; needs JFR class-load and file-read attribution before any claim |
| ModLauncher services → graphics probe | 3.0 s | unknown | the Sodium probe is in the game path; no archived premise |
| Discovery + JarJar | ~8.5 s | possibly (JAR I/O on NTFS, scanning), not shown | **Front F1**: the `DiscoveryStartLocator.locateWrapper()` fallback can open every JAR in `mods/`. The September physical-variance entry left it unmeasured. Measure it with `root_mod_discovery_end − modlauncher_start` on PC and CI. Generic JarJar result cache rejected (#134); copy+SHA sidecar rejected for FML 4.0.43 |
| Layer, AT, config, Mixin prep | ~8.5 s | unknown | **No front yet**: per-config Mixin prepare is NO-GO, AT loop is tiny. Needs the new markers to split it from discovery |
| Vanilla Bootstrap | 4.2 s | no (CPU) | Bootstrap double-transform explained, not a duplicate; no reopen |
| Construction + registration | ~12 s | no (CPU/alloc) | Registrate callbacks, construction scheduler, ModelBakery allocation: all closed. No new premise |

## Measurement still needed

1. Fast PC: 2 runs with this PR's JAR on the Beta instance, `boot_optim.startupLog.enabled=true`, same revision, JFR `settings=profile` for attribution only (not for times). Requires the user's go-ahead and no other launcher or `javaw` running.
2. Hosted CI: the smoke run of this PR gives the same markers on Linux. Use it as the CI column once it reports.
3. Attribute the largest gap (discovery or layer prep) with JFR file-read and class-load events, PC vs CI.

## Reopening criteria

- F1 is worth a bounded investigation only if the PC discovery segment is larger than the CI one by more than the run-to-run spread, and JFR file-read time sits inside `DiscoveryStartLocator`.
- Any change to the layer/Mixin segment needs a new premise: a measured hot spot inside it, not a whole-segment estimate.

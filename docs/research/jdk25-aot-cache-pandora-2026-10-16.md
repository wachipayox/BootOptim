# JDK 25 AOT cache in Pandora: training strategy, gain and failure behaviour — 2026-10-16

Status: **implemented in Pandora** (BootOptimPandora PR #104, one-step training). Follows the
reopening in [`jdk25-aot-cache-reopen-2026-10-15.md`](jdk25-aot-cache-reopen-2026-10-15.md) (#364).
**Still open:** the gain after a *real* pack revision change (see "Not validated").

## Origin of the numbers

Fast PC, Wachiland Launcher, instance "Wachiland Elite BETA", Java 25.0.1 (`java-runtime-epsilon`),
Beta `rev_7d5b9e504f8422494860364f9312da8e` (manifest `16ebb8d1…`), BootOptim JAR
`bootoptim-bench-6b7d84cf.jar`, full affinity mask, `--no-update`,
`-Dboot_optim.benchmark.exitOnTitle=true` (the game exits at the title screen). Endpoint:
`PHASE name=main_menu` of `bootoptim-startup.log` (JVM uptime). One discarded primer first.
Nothing else heavy ran (two idle Gradle JDK 21 daemons were alive). Tools: `tools/fast-pc-bench/`
logic with a local variant that also samples the JVM process tree and the cache files.
"Close" below is wall time from the `main_menu` marker to the end of the game process.

Launchers: series 1 = Pandora #103 build `da9c7aa` with the flags typed into `jvm_flags`;
series 2 and 3 = the PR build (`e5c0f8e`, CI artifact of #104) with no flags.

## 1. Gain (medians of 4, `main_menu`)

| Series | Control | With cache | Δ | Ranges (control / cache) |
|---|---:|---:|---:|---|
| 1a. one-step cache (manual flags), order C A B B A C C A B B A C | 57.70 s | 51.68 s | **−6.03 s (−10.5 %)** | 55.78–59.92 / 50.03–53.22 |
| 1b. two-step cache (manual flags), same order | 57.70 s | 52.00 s | **−5.71 s (−9.9 %)** | 55.78–59.92 / 49.95–53.82 |
| 2. product, first Start trains, later Starts use it (ABBA ×2, control = `WBOMBO_AOT_CACHE=off`) | 55.52 s | 50.48 s | **−5.03 s (−9.1 %)** | 55.17–56.01 / 50.18–51.37 |
| 3. product after a simulated key change and retraining (ABBA ×2) | 54.67 s | 49.86 s | **−4.81 s (−8.8 %)** | 53.98–56.01 / 49.28–51.11 |

`mod_entrypoint` medians: series 1 control 36.63, one-step 32.72, two-step 33.64 s; series 2
control 36.18, cache 33.17 s. In every series no cache run is slower than the fastest control run.

**The −9.25 s of #364 was not reproduced in size:** the cache arms are the same (≈ 50–52 s), the
control arms were 1–3 s faster today (55.5–57.7 s vs 58.7 s). Use −5 to −6 s (≈ −9 to −10 %) as the
gain of this pack/PC. Series 3 is **not** the pending revision check: see below.

## 2. One step or two steps

| | One step (`-XX:AOTCacheOutput`) | Two steps (`AOTMode=record`, later `AOTMode=create`) |
|---|---|---|
| Training run to `main_menu` (control 55.5–57.7 s) | 64.5, 65.2, 65.2, 65.7, 66.4, 69.5 s | 63.1, 67.0, 72.9 s |
| Close after `main_menu` | 33.0, 34.2, 35.0, 35.4, 42.8 s (median 35 s) | 18.1, 18.3, 23.1 s (median 18.3 s) |
| What the JVM does at close | writes a 305–309 MB temporary `<out>.config` (≈ 17 s), then starts a child JVM that assembles the cache (≈ 16–18 s) and deletes the `.config` | writes the 308.6 MB `.aotconf` (≈ 17–21 s) and exits |
| Separate job | none | `create` with the same VM arguments: 14.9 s wall, 12.6 s CPU, 1.05 GB peak RAM |
| `create` running during a game start | — | menu at 54.2 and 56.2 s vs 55.5 s control: no measurable effect on this 16-core PC (create took 17.5 and 18.4 s) |
| Cache | 303.4–304.1 MB | 303.5 MB |
| Gain | −6.0 s (manual), −5.0 s (product) | −5.7 s |

Training cost is the same: both record all class loads and cost about +7 to +17 s on the run that
trains (once per key). Both caches are the same size and give the same gain. The two-step way's only advantage is a close that is ≈ 17 s shorter,
paid for with saved launch arguments, a launcher-start background job and a state machine across
two sessions. **Recommendation: one step.** Reopen the two-step way if real play sessions make the
one-step close far longer than the 35 s measured here (these sessions ended at the title screen; a
long session records more classes and its close was not measured).

Process facts that shaped the design (JDK 25.0.1, checked on a toy program and on the pack):

- The one-step JVM writes `<out>.config` first, then a child JVM writes `<out>` directly, and the
  `.config` is removed only when the assembly finished. A partial cache can therefore exist at
  the requested name, and a finished one is recognisable by the missing `.config`. Pandora never
  asks the JVM for the cache name: it asks for `training/train-<nonce>.aot` and renames it after
  the checks.
- `-XX:AOTMode=auto` (default): a truncated file ("The AOT cache has been truncated"), an empty
  file or a missing file print an error and the JVM starts normally, exit code 0. With
  `-XX:AOTMode=on` the same truncated file aborts the start (exit 1). A changed classpath is
  ignored silently. Pandora uses `auto`.
- The game JVM and its child run in a Windows job object with kill-on-close: killing the
  launcher kills both.

## 3. Failure behaviour of the implementation (PR #104, CI exe `e5c0f8e`)

| Case (how it was forced) | Result |
|---|---|
| First Start, normal exit at the title screen | `ACTIVE … mode=train`, then `mode=trained size_mib=289 seconds_since_start=99–109`; `state.json` + `<key16>.aot` (303.4–304.1 MB), training dir removed |
| Later Starts | `ACTIVE … mode=use key=… size_mib=289`; `-XX:AOTCache=` in the command line, path with spaces works |
| JVM killed when the assembly starts (`taskkill` as soon as `.aot.config` exists) | exit 1 at 16.6 s after the menu → `training discarded: the game exited with Some(1)`, `aot-cache/` left empty, no cache |
| Launcher killed at the same point | the JVM died with it (job object); `training/` left with `run.json` and a 305 MB `.aot.config`. Next Start: `discarding an unfinished training run: assembly-unfinished`, trains again, promoted (`seconds_since_start=99`) |
| JVM killed 8 s after start with a valid cache | exit 1 after 10 s → `FALLBACK … reason=early-exit (exit-code=1 after 10s); retrying without the cache, failures=1`; cache deleted, `failure.json` written; the instance started again 1 s later **without** any AOT flag and ran to the menu (exit 0); no `java25_fallback` was recorded (Java 21 fallback of #103 untouched) |
| Profile revision id changed in the local state (simulation, see below) | `cache for this instance no longer matches its key; removing it to train again`, `mode=train key=<new>`, promoted; the old file is gone |

Also covered by 44 backend unit tests in CI (Windows): key sensitivity per input, stale/partial/
undersized/missing cache, leftover recovery, other-instance independence, failure cap (2 per
key), disk-space guard, Stop handling, retry without Java-21 fallback, not-applicable cases.

## Not validated (open)

- **Gain after a real revision change.** Distribution had no newer Beta revision (the launcher
  reported `UP-TO-DATE rev_7d5b9e50…`; `PACK.md` lists rev 3 as the latest). Series 3 changed only
  the revision id in the instance's committed profile state (backed up and restored, hash
  identical afterwards): it proves invalidation and retraining, **not** that a cache trained on a
  different mod set keeps the gain. Needs a published second revision, then train + ABBA.
- The player's Stop button during training (unit-tested only; it gives an assembling JVM up to
  10 extra minutes before the 3 s kill, once).
- A long real play session (close time, size), a weaker CPU/laptop (training cost, `create`
  contention), two real instances of the same profile (independence is unit-tested; the cache
  lives inside each instance directory).
- Hit counts by class source were not recounted (the #364 numbers stand).

## Reopening criteria

- Close this as a rejection if a real revision change leaves no repeatable gain after retraining.
- Revisit the two-step way if real-session closes are much longer than 35 s, or if players close
  the machine inside the close window often enough that the cache rarely completes.

# JAR opens through JDK ZipFS and `Files.isWritable` before ModLauncher — 2026-10-16

Status: **OPEN** — owner and call count are established (1–2); the read-only attribute experiment (5e) and the attribution of its gain on the main thread (5f) are measured; the writers in the loader stack and in the mod JARs (6) and the Pandora write paths (7) are audited statically. The Pandora proposal (8) is written and **not implemented**. The antivirus split is only partly measured (5b). No optimization is promoted or rejected here.

Measurement environment for the numbers below: fast PC (Windows 11, NVMe), Pandora-launched instance `Wachiland Elite BETA`, Beta revision as stated in each section (5c–5f: `rev_0abae9…`), Java 25.0.1 (`java-runtime-epsilon`), NeoForge 21.1.248 (FML 4.0.43, ModLauncher 11.0.5, BootstrapLauncher 2.0.2, SecureJarHandler 3.0.8). Origin and endpoints come from [#365](https://github.com/wachipayox/BootOptim/pull/365) and [modloading-subphases-pc-vs-ci-2026-10-15](modloading-subphases-pc-vs-ci-2026-10-15.md): start = JVM start, end = `modlauncher_start` (10.0 s on the PC, 3.0 s on hosted CI).

## Premise

The earlier JFR split put 194 native samples in `WindowsNativeDispatcher.GetVolumePathName0` and 13 in `GetFileSecurity0` on the main thread before `modlauncher_start`, all under `ZipFileSystemProvider.getZipFileSystem` → `ZipFileSystem.<init>` → `Files.isWritable`. This entry answers three questions: who opens the JARs and how often, whether the cost is Windows or antivirus, and what could avoid it without changing behaviour.

## 1. Who opens the JARs (binary-verified, versions as installed)

Launch shape (Pandora, from the NeoForge 21.1.248 `version.json` and the Mojang 1.21.1 JVM arguments):

- `-cp ${classpath}` from Mojang's arguments: all non-native library JARs and the client JAR. `legacyClassPath` is `java.class.path`.
- `-p` with 8 JARs (`bootstraplauncher`, `securejarhandler`, `asm`, `asm-commons`, `asm-util`, `asm-analysis`, `asm-tree`, `JarJarFileSystems`) = the boot module layer.
- `-DignoreList=client-extra,${version_name}.jar`. This **replaces** BootstrapLauncher's default `asm,securejarhandler`, so ASM and SecureJarHandler are not skipped.

Call chain before ModLauncher (BootstrapLauncher 2.0.2, `BootstrapLauncher.run`; checked in the binary):

1. `loadLegacyClassPath()` → loop over every classpath entry that exists:
   `JarContents.of(path)` → `JarMetadata.from(...)` → close. **Open #1** of that JAR.
   Entries already on the boot layer are skipped after this open.
2. For each remaining entry, `new JarContentsBuilder()...build()` → `SecureJar.from(jarContents)`. **Open #2** of the same JAR. `SecureJar.from(JarContents)` wraps the existing filesystem and opens nothing new (`Jar(JarContentsImpl, JarMetadata)` has no `newFileSystem` call).

Each `JarContents` opens a `UnionFileSystem` (SecureJarHandler 3.0.8, `JarContentsImpl` constructor → `UnionFileSystemProvider.newFileSystem(filter, paths)`), and `UnionFileSystem`'s constructor calls `FileSystems.newFileSystem(path)` for every backing path that is a file (bytecode of `cpw.mods.niofs.union.UnionFileSystem`, the `FileSystems.newFileSystem:(Ljava/nio/file/Path;)` call; the matching source is `UnionFileSystem.openFileSystem`, `UnionFileSystem.java:93` on SJH `main`).

The JDK side (OpenJDK `jdk-25+36`, same code path in 25.0.1 as far as checked; the Mojang build was not diffed):

- `ZipFileSystemProvider.getZipFileSystem` → `new ZipFileSystem(...)` every time: **no cache**, one constructor per open (`ZipFileSystemProvider.java:120-122`).
- `ZipFileSystem.java:237`: `this.readOnly = forceReadOnly || multiReleaseVersion.isPresent() || !Files.isWritable(zfpath);`
- `forceReadOnly` comes from the **env** map (`accessMode=readOnly`). Nothing in the JVM command line or in a system property sets it. Stock SecureJarHandler passes an empty env, so `Files.isWritable` runs on every open.

After ModLauncher starts, FML discovery opens each mod file through `JarContents` again (FML 4.0 source `ModDiscoverer`; not re-checked in the 4.0.43 binary), and nested JarJar JARs go through `FileSystems.newFileSystem(jij: URI, ...)` in `JarInJarDependencyLocator.loadModFileFrom` (4.0 source). Whether those `jij:` opens reach `Files.isWritable` is **not verified**.

## 2. Count (estimate from the instance on disk)

| Item | Count | Source |
|---|---|---|
| JAR files in `libraries/` | 133 | disk |
| of which native JARs (extracted, not on the classpath) | 24 | disk, `natives` in the name |
| classpath entries BSL loops over | ≈ 109 | 133 − 24; `1.21.1.jar` and `client-extra` are ignored by the NeoForge `ignoreList` |
| already on the boot layer (skipped after open #1) | 8 | the 8 `-p` JARs |
| group builds (open #2) | ≈ 101 | 109 − 8, no `mergeModules` in the arguments |
| **ZipFS constructions before `modlauncher_start`** | **≈ 210** | 109 + 101 |
| mod JARs in `mods/` | 162 | disk |

Consequences:

- **Yes, the same JAR is opened twice** before ModLauncher: once for the module name and once for the module group. This is structural in BootstrapLauncher 2.0.2 and not a Pandora setting.
- The average cost per open is an upper bound: 10.0 s / 210 ≈ 48 ms. The native samples are 4.2 s (194 + 13 samples × 20 ms, if the JFR native period is the default 20 ms; to confirm in the recording settings) ≈ 20 ms per open for the sampled `isWritable` part. The rest of the window is not sampled as Java and is not attributed yet.
- Mod discovery after `modlauncher_start` adds at least 162 more opens, plus the nested JarJar loads (170 recursive loads in the 2026-09-10 FML audit). The earlier PC-vs-CI split found only ≈ 1.3 s of difference in discovery (9.5 s vs 8.2 s). **This does not fit a uniform 20 ms per open**, so the per-open cost is probably not constant. Measure it before reasoning from it.

## 3. Windows or antivirus?

Not decided. What is known:

- `Files.isWritable` on Windows does an ACL check (`GetFileSecurity`) and a volume lookup (`GetVolumePathName`). Linux does a `access(2)`-style check. That explains why it is cheap on hosted CI.
- Whether the cost is in the Windows ACL path itself or in a filter driver (Defender, Controlled Folder Access, cloud sync) cannot be separated from the JFR data.

Plan (the user runs the Defender part; agents do not change security settings):

1. `tools/zipfs-open-bench/ZipOpenBench.java` opens every JAR of one directory with `ZipFileSystemProvider.newFileSystem(path, env)`, the same call stock SecureJarHandler makes, and prints the time per open. Run stock and `accessMode=readOnly` ABBA, on the real `libraries/` under `%APPDATA%`, and on a copy in a folder outside `%APPDATA%`.
2. If the user agrees: add a **temporary** Defender folder exclusion for the launcher folder and the Java runtime folder, rerun step 1, then remove the exclusion. Pandora's shipped feature (`WINDOWS_SECURITY_INTEGRATION.md`) only excludes `Pandora.exe` as a process and does not cover the JARs read by `javaw.exe`, so it is not a test of this mechanism.
3. Only then `tools/fast-pc-bench` with the `modlauncher_start` marker, ABBA, medians.

## 4. Options that could avoid the cost (evaluation, not endorsement)

| Option | Removes `isWritable`? | Verdict |
|---|---|---|
| JVM flag or system property for zipfs | **No.** `accessMode` is a per-open env key; no global switch exists in JDK 25 source. | Not available |
| Pandora launch arguments (`ignoreList`, `mergeModules`, classpath shape) | No. Merging does not reduce opens: the loop opens each path and the group build opens each path again. Skipping JARs changes the loaded set. | Rejected: behaviour change |
| Patch SecureJarHandler so `openFileSystem` passes `accessMode=readOnly` | **Yes** for the BSL and FML opens. Reads are identical; only write calls through the union filesystem would fail. No writer of these JARs was found, but this is not proven for mods. | Candidate, third-party code, not started. Needs a channel: upstream change, `--patch-module` (ships a copy of a third-party class, against the "no copiar dentro de otro" rule) or a `-javaagent` (adds startup cost and touches the JVM setup that the Java 25 AOT cache work in Pandora PR #104 depends on) |
| Read-only attribute on the JARs | Yes, but it changes the user's files and the launcher's ability to update them | Rejected |
| BootOptim code | No. BootOptim runs after `modlauncher_start`, i.e. after the whole window. | Not possible from BootOptim |
| Defender exclusion | Only as a measurement | Not a product change without the user's decision |

## 5. Microbench result (fast PC, 2026-10-16)

Run: `tools/zipfs-open-bench/ZipOpenBench.java`, Mojang `java-runtime-epsilon` 25.0.1, recursive over the instance's `libraries/` under `%APPDATA%` (133 JARs, natives included), ABBA (stock, readonly, readonly, stock), 3 rounds each. Each open is `ZipFileSystemProvider.newFileSystem(jar, env)` + root touch + close. Read-only run; no file was written. The Gradle daemon was the only other Java process; no launcher or game was running.

| Mode | Round | total ms | per open ms |
|---|---|---|---|
| stock | 1 (A) | 3525 | 26.50 |
| stock | 2 | 2301 | 17.30 |
| stock | 3 | 2211 | 16.62 |
| readonly | 1 (B) | 669 | 5.03 |
| readonly | 2 | 663 | 4.98 |
| readonly | 3 | 597 | 4.49 |
| readonly | 1 (B) | 733 | 5.51 |
| readonly | 2 | 909 | 6.83 |
| readonly | 3 | 743 | 5.59 |
| stock | 1 (A) | 3105 | 23.35 |
| stock | 2 | 2947 | 22.16 |
| stock | 3 | 4527 | 34.04 |

Medians per open: **stock 22.8 ms, readonly 5.3 ms**, a difference of about 17.5 ms per open.

Extrapolated to the ≈ 210 opens before `modlauncher_start` (estimate, not the real launch): stock ≈ 4.8 s, readonly ≈ 1.1 s, difference ≈ 3.7 s. That is about a third of the 10.0 s window. It is an upper bound for the `isWritable` share, because the microbench runs with a warm OS cache and does not reproduce BootstrapLauncher's real order, its locks, or its extra work.

### 5b. Same ABBA with a temporary Defender folder exclusion

The user added a Defender folder exclusion on the launcher folder (`%APPDATA%\WachilandLauncher`, which contains the runtime, `libraries` and the instances) for the duration of this run only. The exclusion was added by the user; no security setting was changed by the agent. Same JAR set and harness as above, 3 rounds each, ABBA. The exclusion is to be removed by the user after this run.

| Mode | Round | per open ms |
|---|---|---|
| stock | 1 (A) | 15.82 |
| stock | 2 | 15.09 |
| stock | 3 | 14.91 |
| readonly | 1 (B) | 4.36 |
| readonly | 2 | 3.97 |
| readonly | 3 | 3.80 |
| readonly | 1 (B) | 4.27 |
| readonly | 2 | 4.00 |
| readonly | 3 | 3.80 |
| stock | 1 (A) | 15.39 |
| stock | 2 | 15.56 |
| stock | 3 | 14.79 |

Medians per open, with the exclusion: **stock 15.2 ms, readonly 4.0 ms**, difference about 11.2 ms.

Comparison with section 5 (no exclusion, same machine, measured separately; not paired):

| | No exclusion | With exclusion |
|---|---|---|
| stock median | 22.8 ms | 15.2 ms |
| readonly median | 5.3 ms | 4.0 ms |
| stock − readonly | 17.5 ms | 11.2 ms |

Reading:

- Defender's folder exclusion lowers the stock open by about 7.6 ms and the readonly open by about 1.3 ms. So part of the per-open cost is antivirus, but the larger part is not.
- With the exclusion, `isWritable` still costs about 11 ms per open on this machine. Extrapolated to the ≈ 210 pre-`modlauncher_start` opens (upper bound, same caveats as section 5): about 2.3 s of the window remains attributable to the `isWritable` path, against 3.7 s without the exclusion.
- These are two runs on different moments of the same machine, not paired on the same JAR order. The medians are the reference, not a precise split.

What this does and does not show:

- It reproduces that the per-open cost is dominated by the JDK's `isWritable` path on this machine: the `readonly` mode keeps the open and the central-directory read and drops most of the time.
- It shows that Defender explains part of the cost, not all. That is a partial split, from one folder exclusion.
- It does not cover a copy outside `%APPDATA%` or a copy with the DOS read-only attribute set (variant (e) in the coordinating session's plan). Both still need approval.
- The read-only attribute does not skip `GetFileSecurity`, because the ACL check (line 395 of `WindowsFileSystemProvider.checkAccess`) runs before the attribute check (line 405). It does skip `GetVolumePathName` (line 413), since the attribute check throws first. Any gain from the attribute depends on that.

### 5c. Startup baseline on a fixed Beta revision (fast PC, 2026-10-16)

Measured with `tools/fast-pc-bench` (`run_once.ps1`, `--no-update`, `exitOnTitle`, affinity FFFF, no JVM flags) on Beta revision `rev_0abae9beeefc7d7fa49f3ad03da2e7fd` (the latest published revision, confirmed in the instance manifest), with BootOptim integration `5e1162f` (`boot_optim-bootstrap-0.1.0.jar`), Java 25.0.1, Pandora 103 (`da9c7aa`). One primer and three baseline runs; no candidate.

| Run | `modlauncher_start` | `root_mod_discovery_end` | `dependency_discovery_end` | `bootstrap_start` | `mod_entrypoint` | `main_menu` |
|---|---|---|---|---|---|---|
| primer | 8.89 s | 11.86 s | 18.12 s | 28.59 s | 36.42 s | 55.61 s |
| base 1 | 8.72 s | 12.56 s | 19.01 s | 29.83 s | 37.74 s | 57.32 s |
| base 2 | 8.28 s | 11.28 s | 17.48 s | 27.57 s | 35.76 s | 55.03 s |
| base 3 | 8.57 s | 11.29 s | 17.17 s | 26.04 s | 33.26 s | 51.03 s |

Reading: the `modlauncher_start` window is 8.3–8.9 s on this revision, below the 10.0 s of section 1 (measured on an earlier revision with an earlier build; not a like-for-like comparison). The three baseline runs are a same-revision series, so their spread is the noise estimate for this machine: about 0.6 s on `main_menu`.

Not done: the read-only-attribute experiment on the live instance files (needs a separate decision). Revision and JAR are fixed for any follow-up series.

### 5d. JFR attribution of the window (one run, same revision and build)

One `run_once` startup with `-XX:StartFlightRecording=settings=profile,dumponexit=true` on `rev_0abae9…` and `5e1162f`. The recording starts at JVM start (14:11:44 UTC); the window is 0 to 9.056 s (`modlauncher_start`). Samples were counted per thread and stack from `jfr print --stack-depth 64`. Sampling period assumed 10 ms here; the runtime's `profile.jfc` was checked later (section 5f): 10 ms for Java samples, **20 ms for native samples**. The native-time figures in this subsection are therefore about half of the real time; section 5f corrects them.

Main thread, inside the window:

| Measure | Samples | Approx. time |
|---|---|---|
| Native samples, all frames | 185 | 1.85 s |
| — with `ZipFileSystem` in the stack | 150 | 1.50 s |
| — with `WindowsFileSystemProvider.checkAccess` in the stack | 121 | 1.21 s |
| — top frame `GetVolumePathName0` | 100 | 1.00 s |
| — top frame `GetFileSecurity0` | 5 | 0.05 s |
| Java (execution) samples | 107 | 1.07 s |
| — with `ZipFileSystem` in the stack | 22 | 0.22 s |

All threads: `ZipFileSystem` native samples 237, `checkAccess` 198, `GetVolumePathName0` 164. Most of the rest runs on background threads (JFR, Drippy early window, ForkJoinPool workers) and is not on the main critical path.

Reading:

- Of the 9.06 s window, the main thread has about 2.9 s of samples. The other ~6 s have no samples on the main thread; they are blocked, parked or in the VM, and this run does not attribute them.
- The `ZipFileSystem` native work on the main thread is about 1.5 s (overlapping with `checkAccess`), so the main-thread union is about 1.6 to 1.7 s, above the 1 s threshold set for stopping.
- `GetVolumePathName0` is the largest single native frame on the main thread (about 1.0 s at 10 ms per sample; about 2.0 s at the real 20 ms native period, see 5f), which is consistent with the check order in section 3: the volume lookup runs only when the DOS read-only attribute is not set.
- The number of ZipFileSystem openings is not obtained from this recording; the ~210 figure in section 2 is still a static estimate.

Caveats: one run; sample counts converted to time with an assumed 10 ms period; JFR itself adds overhead to the window.

### 5e. Read-only attribute experiment on the live instance files (fast PC, 2026-10-16)

Scope and safety: the user confirmed the experiment on the real files (no copies). The list was the 295 `.jar` files under the launcher's shared `libraries/` (133) and the instance `mods/` (162, plus the integration JAR `5e1162f` for the run), saved before the change; none had the read-only attribute beforehand. Arm B set `IsReadOnly` on the whole list; a `finally` cleared it on every file after the last run, and the check after the restore found 0 of 295 still read-only. The bench JAR and `info_v1.json` were restored afterwards. No Defender or system setting was touched.

Design: revision `rev_0abae9…` with `--no-update`, integration JAR `5e1162f`, `run_once` with `exitOnTitle`. Order: one primer (A), then ABBA: A B B A A B. Two extra runs with JFR, one per arm.

Startup timings (uptime, seconds):

| Arm | Runs | `modlauncher_start` | `dependency_discovery_end` | `mod_entrypoint` | `main_menu` |
|---|---|---|---|---|---|
| A, no attribute | 8.79, 9.93, 9.33 | 9.33 median | 19.39 median | 38.45 median | 59.69 median |
| B, read-only | 5.02, 6.49, 5.41 | 5.41 median | 11.69 median | 31.97 median | 52.73 median |

The ranges do not overlap on any marker. Median differences: `modlauncher_start` −3.9 s, `dependency_discovery_end` −7.7 s, `mod_entrypoint` −6.5 s, `main_menu` −7.0 s. The JFR runs fall in the same ranges (A 9.2 s and 67.9 s to menu, one run with JFR overhead; B 5.7 s and 57.3 s).

Parity: every run found the same 251 mod lines in `latest.log` and no crash was logged. The instance `crash-reports` folder has the same 7 files as before the series.

JFR, main thread, inside the window (10 ms sampling assumed as before):

| Measure | A (9.16 s window) | B (5.74 s window) |
|---|---|---|
| `ZipFileSystem` in stack | 141 (1.41 s) | 40 (0.40 s) |
| `WindowsFileSystemProvider.checkAccess` in stack | 124 (1.24 s) | 25 (0.25 s) |
| `GetVolumePathName0` (top frame in the volume lookup) | 105 (≈ 2.1 s at 20 ms per native sample; 1.05 s as first converted) | 0 |
| `GetFileSecurity0` | 6 (0.06 s) | 8 (0.08 s) |

Reading:

- The attribute removes the `GetVolumePathName0` cost completely on the main thread. That is the check the code predicts: with the read-only attribute set, `checkAccess` throws before the volume lookup at line 413. It does not affect the ACL check at line 395, which still costs about 0.08 s.
- The start-to-menu gain is about 7 s on this PC, more than the `GetVolumePathName0` share on the main thread. Section 5f attributes most of the rest to monitor waits on the same open path (the loader's lock); what remains is listed there.
- Sample counts are small (one JFR run per arm). The wall-clock ranges are the stronger evidence, and they come from six non-JFR runs.

Equivalence and risks (not verified in full):

- No loader error and the same mod set were observed in arm B. A general claim needs a check that nothing writes into these JARs; section 6 audits the writers statically, and section 7 lists the Pandora paths that would break.
- On Windows, deleting or overwriting a read-only file fails. Any update or removal of these files must clear the attribute first. Pandora does not do that today, and this change was not implemented anywhere.
- The attribute is on files shared by every instance that uses `libraries/`, not only this instance.

Decision: **open, with evidence** (updated in 5f–9). No integration and no Pandora change. Proposal for the owner of the launcher, if it is pursued: set the attribute when Pandora installs or verifies files it owns, clear it before any update, delete or repair, and restore the normal state on uninstall. Before any such change: (1) a second physical run on another machine or the laptop; (2) a runtime check of the writers (the static audit in section 6 is done; see section 9); (3) a user decision on touching shared files. Reopening criteria: any of the three above passes, or a regression in these numbers on the next revision.

### 5f. Where the remaining gain goes (JFR of both arms, no new run)

Source: the two JFR recordings of 5e (`jfrsin` = arm A, `jfrcon` = arm B). Windows: 9.16 s and 5.74 s, from JVM start to `modlauncher_start`. The recording start comes from `jfr summary` (second precision, so the window start is ±0.5 s). Sampling periods from the runtime's `lib/jfr/profile.jfc` (`java-runtime-epsilon`, 25.0.1): `ExecutionSample` 10 ms, `NativeMethodSample` 20 ms, and `JavaMonitorEnter` / `ThreadPark` recorded only from 10 ms. Both arms were parsed with the same script.

Process CPU at `modlauncher_start` (BootOptim's own `JVM` marker, all threads, in the non-JFR runs whose logs were parsed): arm A 5.38 s, arm B 5.31 s. Wall time in the same runs: 9.33 s and 5.41 s. The 3.9 s difference is not computation: the process used the same CPU to reach the same point. The time is waiting.

Main thread, inside the window (samples converted with 10 ms Java / 20 ms native):

| Component | A | B | A − B |
|---|---|---|---|
| Window | 9.16 s | 5.74 s | 3.42 s |
| Native samples | 186 → 3.72 s | 92 → 1.84 s | 1.88 s |
| — top frame `GetVolumePathName0` | 109 → 2.18 s | 0 | 2.18 s |
| — other native top frames | 77 → 1.54 s | 92 → 1.84 s | −0.30 s |
| Java samples | 91 → 0.91 s | 122 → 1.22 s | −0.31 s |
| Monitor waits ≥ 10 ms (`JavaMonitorEnter`) | 1.88 s (23 events) | 0.57 s (14 events) | 1.31 s |
| Neither sampled nor a recorded wait (residual) | 2.65 s | 2.11 s | 0.54 s |

The components add up to the window difference (1.88 − 0.31 + 1.31 + 0.54 = 3.42 s).

**The waits are on one monitor.** In SecureJarHandler 3.0.8, `UnionFileSystemProvider.newFileSystemInternal` holds the monitor of the `fileSystems` map while it constructs a `UnionFileSystem` (bytecode: `monitorenter` at offset 46, `new UnionFileSystem` at 47, `put` at 61–68, `monitorexit` at 78). The `UnionFileSystem` constructor opens every embedded JAR through `FileSystems.newFileSystem(Path)`, so the JDK zipfs open, including `Files.isWritable`, runs inside that lock. `removeFileSystem` takes the same monitor. The loader therefore serializes the JAR opens; the JDK does not do it alone.

Samples whose stack is inside `UnionFileSystem.<init>` (so the lock holder's time, all threads):

| Measure | A, window | B, window | A, to menu | B, to menu |
|---|---|---|---|---|
| Inside the lock, sampled | 4.20 s | 0.74 s | 7.76 s | 1.86 s |
| — `GetVolumePathName0` | 3.48 s | 0 | 5.72 s | 0.06 s |
| Main thread inside the lock | 2.66 s | 0.55 s | 6.22 s | 1.67 s |

Monitor waits on `newFileSystemInternal`, all threads, in the window: A 14.4 s, B 0.6 s. Several threads wait at once, so these totals are not wall time.

Reading:

- With the stock open, every JAR open pays a volume lookup inside the lock. From 109 samples over about 210 opens (section 2, still an estimate), that is about 10 ms per open.
- The opens are serialized, so their costs add up on the critical path, and the other loader threads and the main thread queue behind the lock. The read-only attribute takes the volume lookup out of the lock, so the lock is held about five times less in the window (4.2 s → 0.74 s). The queue largely disappears.
- The main-thread accounting closes: `GetVolumePathName0` (2.18 s) plus monitor waits (1.31 s) is the 3.42 s window difference within sampling noise. Section 5e's "unexplained" remainder came mostly from the 10 ms conversion, and the rest is blocked time that appears as waits, not samples.
- `GetVolumePathName0` samples are wall time inside a native call. The CPU data says they are waits, not work: the call blocks in the kernel or in a filter driver. Antivirus is one candidate. JFR cannot say which. The Defender-exclusion microbench (5b) cut the stock per-open cost from 22.8 ms to 15.2 ms, so part of it is antivirus and part is not.
- Not attributed: the residual 0.54 s, and the 2.1–2.7 s per arm that is neither sampled nor a recorded wait (VM state, or waits under the 10 ms threshold). The monitor totals are therefore a lower bound.

Correction: 5d and 5e converted native samples with 10 ms. With the real 20 ms period, the main-thread `GetVolumePathName0` share is about 2.2 s in arm A (109 samples here; 5e listed 105 from an earlier parser), not 1.0 s.

Caveats: one JFR run per arm; JFR adds overhead (arm A reached the menu at 67.9 s with JFR and 59.7 s without); the window start is ±0.5 s; the sample periods are nominal. This is attribution, not a timing claim. The timing claim stays with the six non-JFR runs of 5e. Section 9 lists the measurement that would separate antivirus from the lookup itself.

### 6. Writers in `libraries/` and `mods/` (static audit)

Question: while the game runs, does anything write, rename or delete a JAR in `libraries/` or `mods/`? If yes, the read-only attribute changes that behaviour.

Method: (a) constant-pool scan of the 24 loader-stack JARs in `libraries/` and of the 162 JARs in `mods/` for write-capable file APIs (`Files.write`, `move`, `delete`, `copy`, `newOutputStream`, `FileOutputStream`, `RandomAccessFile`, `FileChannel`, `WRITE`, `TRUNCATE_EXISTING`), keeping classes that also contain a path literal (`mods`, `libraries`, `.jar`); (b) bytecode of the flagged runtime paths; (c) bytecode of `jdk.nio.zipfs.ZipFileSystem` from the 25.0.1 runtime; (d) the BootOptim source at `feadc6f4`; (e) the access-error lines of the nine start-to-menu logs. This is a heuristic scan, not a data-flow proof.

| Component | What it writes | Verdict |
|---|---|---|
| JDK zipfs 25.0.1 | Opens an existing JAR with a `READ` channel. Writes only in `sync()`, which runs only when `hasUpdate` is set by a modification through the zip API. It then writes a temp file in the JAR's directory and replaces the JAR. | Only code that modifies a JAR through the zip API can write into `libraries/` or `mods/`. With the attribute, `isWritable` is false, so the zip is `readOnly` and such a modification throws `ReadOnlyFileSystemException` instead of replacing the JAR. |
| SecureJarHandler 3.0.8 | `UnionFileSystemProvider` implements write methods (`move`, `delete`, `copy`, `setAttribute`, `newByteChannel`) for union paths. | No startup caller found; not traced to the end. |
| BootstrapLauncher 2.0.2, ModLauncher 11.0.5 | ModLauncher `ClassTransformer` uses `Files.write` / `replace`, likely a class-dump option. | Not reviewed in detail. No JAR writer found. |
| FML 4.0.43, NeoForge 21.1.248 | `ConfigTracker` and `FMLConfig` write under `config/`. `ModDirTransformerDiscoverer` uses move and write. NeoForge `IOUtilities`, `UsernameCache`, `DumpCommand` (server). | No JAR writer found. The last two rows were not reviewed in detail. |
| sponge-mixin 0.8.7 | Export and decompile helpers (`ExtensionClassExporter`, `RuntimeDecompiler`) write to debug folders. | Debug features. Default-off not verified in this audit. |
| Sinytra Connector b17 (in `mods/`) | Reads input JARs (`readAllBytes`, SHA-256). Writes its outputs and sidecars under `FMLPaths.GAMEDIR/.cache/connector` (`ConnectorUtil.CONNECTOR_FOLDER`); its deletes target those cache files. | Verified in bytecode: no write into `mods/` or `libraries/`. Not traced: the destination of Fabric's nested-mod copy (`ModCandidateImpl.copyToDir`). |
| BootOptim (`feadc6f4`) | Only `.minecraft/.bootoptim/` (`mod-scan-cache-v1`, `embedded-mod-v1`, `cache`). The wrapper JAR is read with `ZipFile`. | Verified in source. |
| MCEF | `mcef.libraries.path` and `mcef-cache`: CEF files and a hash temp file. The final directory was not traced. | Open. No JAR write identified. |
| Xaero World Map, Iris, Ixeris | Config files (`configSubFolder`, `iris-excluded.json`, TOML). | Not JARs. |
| Jade, Spark, AnalogAudio, CosmeticArmorReworked, Decocraft, LDLib, FancyMenu fork, RRLS test | `copy` / `delete` calls near the `mods` literal (Spark's ByteBuddy agent writes a temporary JAR). | Not reviewed in detail. |

Hard links: Pandora links files from its content library into instances (section 7, rows 5 and 14). A hard link shares the inode, and the attribute is on the inode, so the library copy would be read-only too. This instance has no `contentlibrary` folder, and none of its 162 mods has more than one hard-link name, so this run is not affected. Any future install that uses a hard link changes the attribute for every link.

Logs: the nine start-to-menu logs (arms A and B, the primer and the JFR runs) contain no `AccessDenied`, `Access is denied`, `ReadOnlyFileSystem` or `FileSystemException` line, and each lists the same 251 mod files.

Conclusion: no loader component was found that writes into `libraries/` or `mods/` during startup. This does not prove that no mod writes there: the 162 mods were scanned for patterns, and the 14 flagged JARs were reviewed only where they touch paths. Stronger evidence would be a run that logs every access error on those paths (section 9).

### 7. Pandora write paths over `libraries/` and `mods/` (static audit)

Scope: `agent/integration-current` at `fe2d7cdd` (Merge PR #103). Locations: `launcher/libraries` is shared by every instance; `launcher/contentlibrary` is the content store; `instances/<name>/.minecraft/mods` is per instance. The attribute is on the file, so a read-only file in `libraries/` affects every instance that uses it.

| # | Where | Operation on an existing managed file | Effect of the read-only attribute | Where it would be cleared |
|---|---|---|---|---|
| 1 | `launch/mod.rs` ≈ 2200–2270, library provisioning | Writes missing artifacts; existing files are trusted (`ProvisionMissing`) | None for existing files. Repair or verify modes that rewrite a file would fail. | Before the write, in those modes |
| 2 | `launch/mod.rs` ≈ 727–745, 786, 840 | `write_safe` into library and install targets (error ignored at 739) | Rename over a read-only destination fails on Windows: library not installed | In `write_safe` |
| 3 | `fs.rs:82` `write_safe` | Temp file, then rename over the target | Same as 2 | In `write_safe` |
| 4 | `fs.rs:383` `fastcopy` | `remove_file(to)` first | `AccessDenied` returned, no fallback | Before the removal |
| 5 | `install_content.rs:335` | `fastcopy(from, mods/x.jar, reflink, hard_link)` | Install fails and is reported (`content_copy_failed`) | Through row 4 |
| 6 | `install_content.rs:344` | `remove_file(replace)`: the old version after an update; error ignored (`let _`) | **The old JAR stays next to the new one, with no error shown.** Two versions of one mod would load. | Before the removal; stop ignoring the error |
| 7 | `install_content.rs:1342`, `1370` | Download with `File::create` (truncates); `remove_file` on hash mismatch (error ignored) | Truncation fails; a corrupt file can stay | Before both calls |
| 8 | `install_content.rs:1061–1088` | Renames of aux sidecars and shader settings (errors ignored) | Renames of read-only files are expected to work (verify on Windows) | Not needed if verified |
| 9 | `profile_layout_flow.rs:1092–1119`, apply | `rename(live → backup)`, `rename(staged → live)` | Renames are expected to work; replacing a read-only destination does not happen here | Verify |
| 10 | `profile_layout_flow.rs:1196–1221`, rollback | `remove_file(live)` for `Install` and `Replace`, then `rename(backup → live)` | Removing a read-only live file fails, so the rollback aborts and the journal stays | Before the removal |
| 11 | `profile_layout_flow.rs:1900–1906`, `remove_regular_file` | Removes a managed file (for example, an update that drops a mod) | Fails; the transaction errors | Before the removal |
| 12 | `profile_layout_flow.rs:1830–1831`, `stage_source` | `fs::copy(source, target)`, then opens `target` for write | Rust's `fs::copy` copies permissions, so a read-only source gives a read-only target, and the write open fails | After the copy, or reset the target |
| 13 | `duplicate.rs:65` | `set_permissions(to, from.permissions())` | Duplicated instances inherit the read-only attribute | Reset on the copy |
| 14 | `duplicate.rs:190` | Hard link to the content library (`fastcopy(…, hard_link)`) | The attribute is shared with the library file | Do not mark linked inodes, or clear through all links |
| 15 | `backend.rs:1059–1064`, frozen-mods restore | `remove_dir_all(mod_dir)` (errors ignored), then `rename(original_mods → mod_dir)` | The removal stops at the first read-only file; the rename then fails. The mods folder is left half removed; the error only reaches the UI. | Recursively, before the removal |
| 16 | `backend.rs:1323`, `1368`, `1373`, prelaunch copies | `fastcopy` from the content library (error ignored); `std::fs::write` for bytes | A stale or missing mod stays silently; the write fails | Before the copy; log an error |
| 17 | `backend_handler.rs:274`, delete instance | `remove_dir_all(instance.root_path)` | Stops at the first read-only file; the instance folder is half deleted. Libraries are outside the instance root. | Recursively, before the removal |
| 18 | `backend_handler.rs:793`, enable or disable a mod | `rename(x.jar ↔ x.jar.disabled)` | Expected to work (verify on Windows) | Not needed if verified |
| 19 | `distribution.rs:1164`, `1398`, object cache | Rename of a temp object; `remove_file` of a corrupt object | Fails if objects are marked | Do not mark cache objects |
| 20 | `launcher_import/*`, `copy_content_recursive` | Copies external instance folders into new instances | Depends on how the copy sets permissions (not traced) | Reset on import (not traced) |

Summary: rows 2, 4, 6, 7, 10, 11, 15 and 17 fail, or stop halfway, when their target is read-only. Row 6 is the most dangerous because it fails silently. Rows 12 and 13 spread the attribute to new files. Row 14 spreads it to other links. Rows 1, 8, 9 and 18 are expected to work, but only the rename rows need a Windows check.

Repository check: Pandora's CI has Windows jobs (seven `windows-2022` and one `windows-latest` workflow runs in `.github/workflows`), so a test on Windows is possible without a new runner.

## 8. Proposal for Pandora (written, not implemented)

- **Scope.** Only JARs that Pandora installed for a managed revision and checked by hash (manifest entries under `libraries/` and `mods/`). Never configs, saves or user files.
- **Set.** After the transaction commits and the live file's hash is verified (row 9). Never on staged or backup files, and not on hard-linked inodes (row 14) unless the content library is changed too.
- **Clear.** One helper, for example `managed_clear_readonly(path)`, called before every mutating operation on a managed path: `fastcopy`, `write_safe`, the removals in `install_content` (old version, corrupt download), the rollback and removal steps in `profile_layout_flow`, the frozen-mods restore, instance deletion (recursive), and the copy in `stage_source` and `duplicate` (the target inherits the attribute). The helper returns an error that names the path. Call sites that ignore errors today (`let _ =`) log a WARN and surface the failure.
- **Kill switch.** A launcher setting, off in the first release. With it off, the code still clears any attribute it finds, so switching it off leaves no read-only files behind.
- **Recovery.** At startup, clear the attribute from files that are not in the active manifest, so a crash between clear and set cannot leave orphans read-only.
- **Logging.** `[WBOMBO-COMPAT] ACTIVE project=Pandora mechanism=readonly-managed-jars` at INFO when enabled; `[WBOMBO-COMPAT] FALLBACK project=Pandora mechanism=readonly-managed-jars reason=<clear|set failed>` at WARN.
- **Manual changes.** Explorer asks for confirmation before it deletes or overwrites a read-only file. After a confirmed delete, the next launch finds the file missing and reprovisions it. An edited file is detected only if the verification policy checks hashes, which library provisioning does not do today (row 1). The attribute does not change that.
- **Other tools.** Backup and sync tools may skip or fail on read-only files. Git and archivers are not affected.
- **Platform.** Windows only (`cfg(windows)`). The Linux and Android paths are untouched.

Risks: a crash between clear and write; hard links; the antivirus interaction (5f, section 9); the silent errors of rows 6, 7 and 16; mods that write into their own JARs, which were not found but not proven absent (section 6).

Validation before any integration:

1. Unit tests for the helper, and one test per row of section 7 with a read-only target, on the Windows CI runners.
2. A scenario test: install, update, rollback, remove, duplicate, frozen restore and instance delete, each with the managed set read-only.
3. A second physical machine (the laptop or another PC) with the same ABBA protocol as 5e, on one fixed revision.
4. Stop rules: no integration if a scenario leaves a silent stale file or a half-removed folder, or if the second machine does not reproduce a gain of at least 3 s to `modlauncher_start`.
5. A user decision: whether Pandora may change files in the shared `libraries/`, and whether the feature ships off or on.

Recommendation: keep open. The next step is the cheap measurement in section 9. The proposal is about twenty call sites in Pandora and should not start before the gain is reproduced on a second machine.

## 8b. Alternative without touching files: a read-only Java agent (2026-10-16)

Branch `claude/zipfs-readonly-agent-2026-10-16` (BootOptim, `tools/zipfs-readonly-agent/`, CI job `zipfs-readonly-agent-selftest`, not integrated). The agent prepends one call to `jdk.nio.zipfs.ZipFileSystemProvider.getZipFileSystem(Path, Map)`: for a plain `.jar` open under a `mods/` folder or below a `libraries/` folder, it adds `accessMode=readOnly`. The JDK then sets `readOnly = true` and does not call `Files.isWritable`. That is the same zip state the attribute produces (section 5f), with no disk change.

Viability, checked:

- The self-test passes on the JDK 25.0.1 runtime that Pandora ships (a local run that does not start the game): without the agent the mod and library JARs are writable; with it they are read-only, a JAR outside the managed roots stays writable, a caller that passes `create` or `accessMode` keeps its own behaviour, and a write through zipfs fails with `ReadOnlyFileSystemException`. The same test runs on `windows-latest` with Temurin 25 in the CI job.
- Wiring that was needed: the policy class is on `Boot-Class-Path` so the boot loader sees it; `jdk.zipfs` is defined by the platform loader in this JDK; the agent gives `jdk.zipfs` read access to the boot unnamed module (`Instrumentation.redefineModule`); the transform uses the JDK 25 class-file API, so no ASM is bundled.
- Fail open: any failure in the transform or the policy leaves stock behaviour and logs `[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=zipfs-readonly-agent`.

Comparison with the attribute:

| Aspect | Attribute on the files (5e) | Agent (`-javaagent`) |
|---|---|---|
| Disk and shared state | Changes 295 files shared by every instance | None |
| Pandora paths to change | About 20 call sites (section 7) must clear before writing | None |
| Silent failures | Old version kept next to new (row 6); stale mod (row 16) | Fails open to stock, with a log line |
| Scope | Every open of the file by any process | Plain `.jar` opens in this JVM under managed roots |
| Launcher change needed | None | `-javaagent:<path>` in `jvm_flags` (Pandora already passes flags) |
| Depends on | Windows attribute semantics | One private JDK method, matched by name and descriptor; the bundled JDK is fixed (25.0.1) |
| Measured timing | Yes (5e, six non-JFR runs) | Not yet (8c) |
| Risk to startup itself | None known | `-javaagent` may change CDS or class loading: unmeasured |
| Writers | Any writer of those files fails | Only zipfs writers without `create` on managed JARs fail |

On writers: the constant-pool scan of section 6 lists the `create` flag in Sinytra Connector (`FileSystemUtil`, `JarTransformInstance`, `RefmapRemapper`), Iris, Minecraft (`FileZipper`, `LinkFSProvider`) and JarJarFileSystems. None was traced to a managed JAR, and the policy leaves every open with `create` untouched by construction. The residual risk is a zipfs writer without `create` on a managed JAR, which was not found.

Recommendation, conditional on the measurement in 8c: the agent removes the disk-side risks of section 7 and keeps the zip semantics that were measured. If the ABBA confirms the gain, it is the preferred candidate over the attribute, and the Pandora change in section 8 would not be needed for it. Neither is integrated.

## 8c. ABBA of the agent, prepared for the next PC turn (not run)

Not run on the PC: no PC use in this turn.

Hosted check, no PC: [#387](https://github.com/wachipayox/BootOptim/pull/387) (draft, experiment only) adds a `-javaagent:@ZIPFS_AGENT_JAR@` candidate directive to the exact-pack A/B (one repetition, stock control) and builds the agent in the job. It checks, on the hosted Linux runner, that the agent does not break startup: menu reached, one `ACTIVE`, no `FALLBACK`, no access-error exceptions, same `Found mod file` count as the control. Linux `isWritable` is cheap, so no gain is expected there. Its result is pending and is not recorded here yet.

- Artifact: `tools/zipfs-readonly-agent/run-selftest.ps1` writes `build/zipfs-readonly-agent.jar` (needs a JDK 25 with `javac`). Copy that JAR to `C:/BootOptimBench/zipfs-agent/zipfs-readonly-agent.jar`, outside the instance.
- Arms, all on `rev_0abae9…`, integration JAR as in 5e, with no read-only attribute on any file:
  - S, stock: `jvm_flags` empty.
  - G, agent: `jvm_flags` = `-javaagent:C:/BootOptimBench/zipfs-agent/zipfs-readonly-agent.jar` (forward slashes).
- Order: one primer S, then A B B A A B as in 5e, with `run_once` `exitOnTitle` and mask `FFFF`. Restore `info_v1.json` and the bench JAR afterwards, as in 5e.
- Per-run checks: arm G has one `[WBOMBO-COMPAT] ACTIVE` line and no `FALLBACK` line; arm S has neither; 251 mod lines; no crash; no `ReadOnlyFileSystemException`, `AccessDenied` or `FileSystemException` in `latest.log`.
- Metrics: medians of `modlauncher_start`, `dependency_discovery_end`, `mod_entrypoint` and `main_menu`, the process CPU at `modlauncher_start` (CPU against wall), and the uptime of the first JVM marker against the JFR zero, to see any startup cost from `-javaagent`.
- Stop rules: any `FALLBACK` line in arm G, or a crash, makes arm G invalid until fixed. A mod count change makes it a failure.
- Decision: a gain of at least 3 s to `modlauncher_start` with non-overlapping ranges, and no startup cost from the agent, makes it a candidate that needs a second machine before any integration. Otherwise document it as rejected with these numbers.

**Result (run 2026-10-16, fast PC, single session, PC free afterwards).** Executed as planned, with two changes: the bench JAR `bootoptim-bench-6b7d84cf.jar` stayed in `mods/` in both arms (it emits no `modlauncher_start` marker, so only `mod_entrypoint` and `main_menu` are compared), and the arms were run as one primer plus S, G, G, S, S, G after two launches were refused by the launcher-running check (a retry added a launcher-exit wait). Three stock and three agent runs remain.

| Arm | Runs | `mod_entrypoint` median | `main_menu` median |
|---|---|---|---|
| S, stock | 36.4, 34.2, 34.6 s | 34.6 s | 54.3 s (56.4, 54.3, 54.1) |
| G, agent | 27.7, 27.2, 26.3 s | 27.2 s | 47.1 s (47.7, 47.1, 45.9) |

Median differences: `mod_entrypoint` −7.4 s, `main_menu` −7.2 s. The ranges do not overlap on either marker. The primer (54.4 s) is in the stock range.

Checks: every run loaded 251 mod files; no `ReadOnlyFileSystemException`, `AccessDenied` or `FileSystemException` in any `latest.log`; the instance `crash-reports` count stayed at 7; `info_v1.json` was restored (SHA-256 identical to the start). A command-line watcher on the game `javaw` captured `-javaagent:C:/BootOptimBench/zipfs-agent/zipfs-readonly-agent.jar` in the agent run, so the flag reaches the game JVM.

Not verified: the agent's own `ACTIVE` and `FALLBACK` lines are written to `System.err`, which the launcher does not capture into `latest.log`, so the active transform is inferred from the timing (a gain of the same size as the attribute in 5e, −7.0 s), not from a log line. No CPU-against-wall split was taken (the bench JAR has no `modlauncher_start` marker). The bench JAR is not the 5e integration JAR, so these numbers are comparable only inside this series.

Decision under the stated rules: the gain is well above 3 s and the ranges do not overlap, so the agent is a candidate that needs a second machine and a confirmed `ACTIVE` path before any integration. Not integrated.

## 9. Next PC turn (measurements, only when the user gives the turn)

1. **Per-call timing, no game launch.** Time `Files.getFileStore(path)` over the 295 JARs of 5e, twice each, in two arms: no Defender exclusion, and a Defender folder exclusion created by the user. `getFileStore` calls the volume lookup that `checkAccess` uses (`WindowsFileStore.create`); confirm that with a JFR of the microbench. If the wait is antivirus, the per-call time falls with the exclusion. If it is the lookup itself, it stays high with the exclusion. Report the median and the 95th percentile per call.
2. **Which driver, only if step 1 shows a large gap.** Process Monitor needs administrator rights, so it is the user's decision.
3. **Second machine, only if step 1 shows the gap and the user agrees.** The ABBA of 5e on one fixed revision, on another machine.

## Reopening criteria

Reopen with a runtime change only if all hold:

- the microbench (stock vs `readOnly`, ABBA, 2+2, same folder) shows a per-open saving that accounts for most of the 10 s window on the fast PC, or the saving disappears under a Defender folder exclusion (then the cause is the antivirus: document it and let the user decide, do not change Defender here);
- the owner of the change is named: an upstream SecureJarHandler change (outside wbombo), or a user decision for a patch channel; and
- the compatibility argument is written: read-only only affects writes, and any `[WBOMBO-COMPAT]` fallback is defined.

## Result

**Decision: integrate the agent path as an optional, flagged mechanism; the read-only attribute is not integrated.** Decided on 2026-10-16 by the coordinator, with the user's approval to continue; the final switch on by default needs the user's PC validation of the Pandora build.

- Agent: BootOptim [#387](https://github.com/wachipayox/BootOptim/pull/387) (draft; tool, status file, CI artifact, and the hosted exact-pack gate of section 8c; the exact-pack gate passed per the coordinator's review).
- Launcher: BootOptimPandora [#105](https://github.com/wachipayox/BootOptimPandora/pull/105) (draft): `-javaagent` for Wachiland Java 25 starts on Windows, JAR embedded with its source commit and SHA-256, switch `WBOMBO_ZIPFS_AGENT=0`, `[WBOMBO-COMPAT]` lines in `launcher.log`, and the agent's own status file.
- Attribute (section 5e, section 7): stays documented and out of integration; it needs about twenty Pandora changes and a user decision on shared files.
- Default: **on** for Wachiland Java 25 starts on Windows (user decision, 2026-10-16); `WBOMBO_ZIPFS_AGENT=0` switches it off. Still open: the PC validation of the Pandora build (launch with ACTIVE, the A/B against the switch, and a repaired JAR), and a second machine for the ABBA.

Evidence summary: the main menu is about 7 s earlier with the agent on the fast PC (8c, three runs each); hosted Linux runs show no breakage; no loader writer was found (section 6).

## Links

- [#365](https://github.com/wachipayox/BootOptim/pull/365): PHASE markers that produced the 10.0 s segment.
- [modloading-subphases-pc-vs-ci-2026-10-15](modloading-subphases-pc-vs-ci-2026-10-15.md): the segment table and the "outside BootOptim" verdict this entry refines.
- [fml-4.0.43-jij-filesystem-2026-09-10](../archive/research/fml-4.0.43-jij-filesystem-2026-09-10.md): the `jij:` path (PR #244).
- [modlauncher-prebootstrap-redundancy-2026-09-09](../archive/research/modlauncher-prebootstrap-redundancy-2026-09-09.md): later ModLauncher phases, not this window.

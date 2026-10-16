# JAR opens through JDK ZipFS and `Files.isWritable` before ModLauncher — 2026-10-16

Status: **PROFILED** — static attribution of the owner and of the call count is complete, and a first physical microbench is in (section 5). The antivirus split (Defender exclusion) is **not measured yet**. No optimization is proposed, promoted or rejected here.

Measurement environment for the numbers below: fast PC (Windows 11, NVMe), Pandora-launched instance `Wachiland Elite BETA`, Beta rev 3, Java 25.0.1 (`java-runtime-epsilon`), NeoForge 21.1.248 (FML 4.0.43, ModLauncher 11.0.5, BootstrapLauncher 2.0.2, SecureJarHandler 3.0.8). Origin and endpoints come from [#365](https://github.com/wachipayox/BootOptim/pull/365) and [modloading-subphases-pc-vs-ci-2026-10-15](modloading-subphases-pc-vs-ci-2026-10-15.md): start = JVM start, end = `modlauncher_start` (10.0 s on the PC, 3.0 s on hosted CI).

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

## Reopening criteria

Reopen with a runtime change only if all hold:

- the microbench (stock vs `readOnly`, ABBA, 2+2, same folder) shows a per-open saving that accounts for most of the 10 s window on the fast PC, or the saving disappears under a Defender folder exclusion (then the cause is the antivirus: document it and let the user decide, do not change Defender here);
- the owner of the change is named: an upstream SecureJarHandler change (outside wbombo), or a user decision for a patch channel; and
- the compatibility argument is written: read-only only affects writes, and any `[WBOMBO-COMPAT]` fallback is defined.

## Result

**Open.** Owner and count are established. Decision waits for the physical microbench. No runtime code is added.

## Links

- [#365](https://github.com/wachipayox/BootOptim/pull/365): PHASE markers that produced the 10.0 s segment.
- [modloading-subphases-pc-vs-ci-2026-10-15](modloading-subphases-pc-vs-ci-2026-10-15.md): the segment table and the "outside BootOptim" verdict this entry refines.
- [fml-4.0.43-jij-filesystem-2026-09-10](../archive/research/fml-4.0.43-jij-filesystem-2026-09-10.md): the `jij:` path (PR #244).
- [modlauncher-prebootstrap-redundancy-2026-09-09](../archive/research/modlauncher-prebootstrap-redundancy-2026-09-09.md): later ModLauncher phases, not this window.

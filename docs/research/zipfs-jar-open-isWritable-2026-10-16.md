# JAR opens through JDK ZipFS and `Files.isWritable` before ModLauncher — 2026-10-16

Status: **PROFILED** — static attribution of the owner and of the call count is complete. The per-open cost and the antivirus split are **not measured yet**; they need a physical fast-PC run (the PC was in use by another session). No optimization is proposed, promoted or rejected here.

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

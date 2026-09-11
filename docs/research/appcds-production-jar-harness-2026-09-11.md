# AppCDS production JAR-only hosted harness — 2026-09-11

Status: **DIAGNOSTIC HARNESS / NO CDS PROMOTION**

Follow-up to PR #260. That research established that packaged AppCDS can be fail-open, but the existing `runPackBenchmarkClient` path is a ModDevGradle development launch with source/output directories and therefore cannot answer whether a production distribution archive is usable. This change adds the missing hosted Linux surrogate without enabling CDS for users or distributing any archive.

## What this harness represents

The harness materializes an isolated copy of the pinned exact-pack fixture and injects the actual distributable BootOptim bootstrap from `bootstrap/build/libs/`. It then builds a normal launcher root from Mojang launchmeta plus the pinned NeoForge `21.1.248` installer. The installer is executed with `--install-client`, so its official processors generate the patched client artifact and all launcher/profile libraries used by the 1.21.1 production distribution.

The Java command is then derived from the installed vanilla + NeoForge launcher profiles. The Java class path and module path contain only JARs; Gradle source-set/output directories are rejected. NeoForge's production `cpw.mods.bootstraplauncher.BootstrapLauncher`, FML arguments, module path, opens/exports, JarJar, Connector and ordinary mod discovery execute from the distribution artifacts instead of ModDevGradle.

This is deliberately **not claimed to be Prism-equivalent**. Prism's parent `NewLaunch.jar` / ForgeWrapper process and Prism's exact component-merging/order implementation are not reproduced by hosted. The manifest records that boundary explicitly. A future Windows promotion still requires the exact Prism command line and archive identity from the real target instance.

## Reproducibility and identity

The harness records:

- exact-pack fixture SHA-256;
- OS/architecture/kernel identity;
- Oracle runtime executable and `release` hashes plus `java -version` output;
- NeoForge installer, vanilla version JSON and installed NeoForge version JSON hashes;
- the complete ordered Java class path, with size and SHA-256 for every JAR;
- all top-level mod JAR hashes after injecting the packaged BootOptim bootstrap;
- exact JVM arguments, main class and game arguments;
- the reference `options.txt` hash;
- generated archive size/SHA-256 after training.

The NeoForge installer itself is downloaded from the official NeoForged Maven and verified against its Maven checksum sidecar. Mojang launchmeta, client, libraries, natives and assets are verified against their published SHA-1 entries. Installer-generated local artifacts remain covered by the final SHA-256 identity manifest.

## Java-agent prohibition and fail-open gate

Before archive generation, the harness rejects `-javaagent`, `-agentlib`, `-agentpath`, `-XX:+AllowArchivingWithJavaAgent`, and agent-bearing `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` or `JDK_JAVA_OPTIONS`. The child game JVM is launched with those implicit-option environment variables removed so the recorded command is complete.

The archive is generated only with:

```text
-Xshare:auto
-XX:ArchiveClassesAtExit=<temporary-hosted-path>
```

Consumption uses only an exact strong-identity match and then adds:

```text
-Xshare:auto
-XX:+VerifySharedSpaces
-XX:SharedArchiveFile=<temporary-hosted-path>
```

A deliberate stale-identity self-test mutates one expected mod hash and verifies that the gate returns **no CDS flags**. This is the fail-open behavior: stale/missing identity means stock Java arguments, never `-Xshare:on` and never a fatal archive requirement. The exact-match branch must activate before the hosted consumption smoke can pass.

## Runtime smoke gates

Archive generation and archive consumption are separate fresh Java processes. Both must reach BootOptim's existing `main_menu` endpoint. After each process exits, the harness validates the exact-pack resource selection and requires exactly one effective resource reload. The consumption run additionally rejects BootOptim/Mixin apply/prepare failures.

`-Xlog:cds=info,class+load=info` is captured for the consumption process. A smoke is valid only if at least one shared class from the application/runtime prefixes (`net.neoforged`, `cpw.mods`, Sponge Mixin, Minecraft or BootOptim) is reported as loaded from the shared objects file. Mapping the archive while observing only JDK base-CDS hits is not accepted as AppCDS consumption evidence.

No timing A/B is started by this harness. The first performance comparison is allowed only after this functional gate proves a non-empty archive, exact-match activation and real application/shared-class hits.

## Hosted workflow and archive handling

`.github/workflows/appcds-production-jar-harness.yml` runs on Ubuntu 22.04 with the same Xvfb/llvmpipe and pinned Linux JCEF support used by exact-pack hosted CI. It builds the packaged bootstrap, prepares an isolated fixture, executes the JAR-only archive generation/consumption smoke, and uploads only manifests/logs/identity evidence.

The temporary `.jsa` is explicitly deleted before artifact upload and is never published. No user launcher config, shipped mod code or Java installation is changed.

## What hosted still cannot prove

This is a Linux software surrogate. It does **not** establish:

- Windows archive compatibility or page-mapping/storage behavior;
- the exact Prism `NewLaunch.jar` / ForgeWrapper parent-launch topology;
- relocation across real Prism instance roots;
- performance benefit on the target laptop;
- production safety of an archive built with any different Oracle JDK build, launcher ordering, pack fingerprint or JVM arguments.

Those remain promotion gates from #260. If hosted fails to produce application/shared-class hits, the correct result is to close the performance path for this topology or investigate why the exact production-like loader classes are not archive-eligible; it is not a reason to weaken identity or bypass ModLauncher/Mixin.

# Packaged AppCDS / CDS archive feasibility — 2026-09-11

Status: **VIABLE PREMISE / NOT PROMOTABLE YET**

Agent 127 investigated whether the exact NeoForge 1.21.1 pack can ship a pre-generated HotSpot CDS/AppCDS archive without installing or modifying Java on the user's machine. The answer is narrower than “add `-Xshare`”: a fail-open launch path is architecturally possible, but a production archive must be generated and consumed on the exact production Prism/Windows/JDK tuple, and current hosted exact-pack CI cannot produce an archive that is valid evidence for that tuple.

## Established project constraints

BootOptim's target is process-start to a usable main menu. Existing work already places material time in class initialization, ModLauncher/Mixin transformation, FML discovery/construction and the model/resource pipeline. CDS is therefore interesting only as a way to reduce class loading/parsing/verification/linking work; it must not move class initialization, callbacks, Mixin application, FML scheduling or live mod state earlier.

The current hosted exact-pack fixture is Linux/Xvfb/llvmpipe with Oracle JDK 25.0.4 and `-XX:ActiveProcessorCount=4`. It is a software-pack surrogate, not the Windows laptop. The `runPackBenchmarkClient` path is a ModDevGradle development launch whose BootOptim classes are supplied from Gradle source-set output directories.

## What HotSpot guarantees, and what it does not

Oracle JDK 25 documents that AppCDS can be used by the built-in loaders and by custom class loaders. A dynamic archive can be created with `-XX:ArchiveClassesAtExit=<file>` and later consumed with `-XX:SharedArchiveFile=<file>`. `-Xshare:auto` is the production-safe mode: an unusable archive is skipped instead of making archive use mandatory; `-Xshare:on` is explicitly a testing option and is not acceptable for BootOptim fallback semantics.

CDS archives are JDK-build-specific. HotSpot also requires archive creation and use to have a compatible class path; JARs on the class/module path must not have changed. Non-empty class-path/module-path directories are not supported for application archive creation/use. These restrictions are important here because the current hosted ModDevGradle launch has development output directories and is not the production Prism launch topology.

`-XX:+VerifySharedSpaces` provides an additional archive integrity check, but it is not a replacement for the pack's own SHA-256 identity/integrity gate.

### ModLauncher, Mixin and custom loaders

The custom-loader support is the reason CDS is not automatically incompatible with NeoForge/ModLauncher. The safe premise is **not** “skip Mixin transformation”. ModLauncher/Mixin must still run and present the class bytes it would normally define. CDS may then let HotSpot reuse archived class metadata for byte-identical definitions. A candidate is invalid if logs or probes show fewer expected transformation requests/callbacks merely because an archive bypassed them.

This must be validated against the exact production launch. BootOptim must not add a cache of transformed classes, live class-loader state, mod objects or Mixin results.

### Java/JVMTI agents

A pack-level Java agent is a separate risk. HotSpot's archive-dump path normally rejects Java agents unless the diagnostic `-XX:+AllowArchivingWithJavaAgent` path is enabled; transformed classes can otherwise make an archive semantically different from their original class files. BootOptim should **not** promote an archive generated with `AllowArchivingWithJavaAgent`.

Promotion rule: the training and consumption command lines must contain no `-javaagent`, `-agentlib` or `-agentpath` entry capable of class-file transformation. If the exact Prism profile contains one, packaged AppCDS is NO-GO until an official JDK workflow can prove those transformed classes are excluded safely. ModLauncher/Mixin themselves are not JVM agents and are evaluated separately through the custom-loader contract above.

## Prism launch integration

Prism has the necessary ordering without modifying Prism or Java:

1. Prism runs the instance Pre-Launch command with the game root as working directory.
2. Later, `LauncherPartLaunch::executeTask()` computes the Java arguments and starts the selected Java executable, also from the game root.
3. Prism's user JVM arguments are passed through as ordinary arguments; its JVM-argument validation only rejects conflicting memory/version options.
4. The Java launcher supports `@argfile` expansion.

Therefore an instance can contain one stable JVM argument such as:

```text
@bootoptim-appcds.args
```

The packaged Pre-Launch gate rewrites that file **atomically before Java starts**. Exact match writes only:

```text
-Xshare:auto
-XX:+VerifySharedSpaces
-XX:SharedArchiveFile=<absolute path to the packaged archive>
```

Any mismatch, malformed manifest, missing archive or gate error writes an empty file. The Minecraft JVM then starts stock, apart from expanding an empty argument file. The gate must clear the file first so a crash cannot leave a stale enabled archive from a previous launch.

The diagnostic helper added with this research (`tools/appcds/archive_identity.py`) models exactly this manifest/gate behavior. It is repository tooling, not the final Windows pre-launch implementation; promotion should port the same schema to a packaged PowerShell/helper path that requires no user installation.

## Required archive identity

Archive activation must be stronger than HotSpot's own class-path check because NeoForge discovers many mod/JarJar inputs outside the initial Java class path.

The manifest must bind at least:

- pack fingerprint/version;
- OS and architecture;
- Java `release` file SHA-256 and Java executable SHA-256, plus recorded vendor/version/runtime/build fields;
- the **ordered production Java class path** as emitted by Prism, including Prism `NewLaunch.jar`, Minecraft/NeoForge bootstrap libraries and every other launch JAR;
- every top-level mod JAR by SHA-256; hashing a parent mod JAR covers the bytes of embedded JarJar children, but the exact selected/extracted child set should still be recorded diagnostically;
- Connector/Sinytra/FML/ModLauncher/Mixin/BootOptim JARs and any launcher component that can affect transformed class bytes;
- the archive itself by SHA-256 and size;
- the exact JVM option set that can change CDS compatibility, including heap/compressed-oops/GC/module options and the absence of Java/JVMTI agents.

The manifest itself must be covered by the modpack distribution's authenticated fingerprint/signature/checksum. Otherwise local tampering with both archive and manifest is outside the integrity model. `VerifySharedSpaces` is defense-in-depth against archive corruption, not a trust root.

A cheap size/mtime cache may be considered only as an optimization of the pre-launch verifier after a full strong-hash pass has established the sidecar. It must invalidate on path/size/mtime/file identity change and fall back to re-hashing; it must never treat metadata alone as the security identity.

## Generation workflow

A distributable archive is generated only from a production-style Prism launch, not from ModDevGradle:

1. Pin the exact Prism instance, exact enabled mods/config/components, Java vendor/runtime/build, OS/arch and JVM args.
2. Record the final Prism Java command line and ordered class path.
3. Assert that no unsupported module-path option and no Java/JVMTI agent is present.
4. Launch the real pack with `-Xshare:auto -XX:ArchiveClassesAtExit=<staging>.jsa` and CDS logging enabled. Exercise the normal route to the first usable main menu and then terminate cleanly so the dynamic archive is written.
5. Record archive SHA-256, size, archived/shared-class counts and all skip/rejection diagnostics.
6. Re-launch the same instance with the archive through the fail-open gate and prove that the archive is actually mapped/used.
7. Run stock and candidate from fresh processes with identical pack/JVM state. The generation/training launch is **not** a benchmark sample.
8. Before distribution, copy/move the entire Prism instance to a different absolute root and repeat archive-use validation. Oracle documents that creation/runtime class paths must match; do not assume a Prism archive is relocatable across installations until this explicit gate passes.

Do not use `-XX:+AutoCreateSharedArchive` as the production design. It is useful for developer experimentation, but it would allow a user's first run or a post-JDK-change run to write/replace an archive and would weaken the proposed immutable, versioned pack artifact and explicit promotion gate.

## Measurement contract

The performance endpoint remains BootOptim's process-start marker to `main_menu` / `main_menu_presented` under the same measurement origin. The pre-launch identity gate is outside the Minecraft JVM, so promotion must report two values:

- **Minecraft process start -> usable menu**, to isolate CDS itself;
- **Prism pre-launch gate start -> usable menu**, to ensure strong hashing/gating does not erase the user-visible win.

Candidate logs must also capture `-Xlog:cds=info,class+load=info` (or an equivalent bounded diagnostic run) proving nonzero application/custom-loader shared-class use. A timing improvement without actual archive hits is invalid.

The A/B acceptance bar is a coherent reduction in end-to-end startup with unchanged resource selection/reload count, zero BootOptim/Mixin errors, unchanged expected transformation/failure surfaces, and no new fallback/warning suppression. Class-init timing may become cheaper due to previously parsed/verified class metadata, but class initialization order and side effects must remain stock.

## Hosted exact-pack boundary

Current GitHub exact-pack CI can prove only the following parts of this design:

- the identity/gate implementation is deterministic and fail-open;
- Oracle JDK 25.0.4 on hosted Linux supports the intended CDS flags;
- a Linux archive generated and consumed on the same Linux/JDK/production-like class path can be A/B tested if such a launcher path is added later;
- unchanged BootOptim resource/Mixin/runtime contracts on ordinary smoke runs.

It **cannot** produce an archive that can be shipped to or used as performance evidence for the Windows laptop. More importantly, the current exact-pack run is a ModDevGradle development launch with non-empty class/output directories on the Java class path, which violates the documented AppCDS application-archive path restriction and is not Prism's production class path. Forcing a CDS A/B through that runner would answer the wrong question.

Accordingly this PR does not add an `[exact-pack-ci]` AppCDS A/B trigger. The first valid performance A/B must use a production-style packaged launch on the same OS/JDK build as the archive. Hosted may gain a separate production-launch harness later, but that is infrastructure work, not a reason to weaken the archive contract.

## Promotion / NO-GO decision

**Keep as a viable invasive research direction; do not promote yet.** The architecture has a clean fail-open launcher path and does not require Java installation/modification, but there are three hard gates before performance testing can justify shipping it:

1. prove a dynamic archive generated by the exact production Prism/NeoForge launch contains and reuses a material number of application/custom-loader classes while preserving the full ModLauncher/Mixin/FML failure/transform surface;
2. prove the archive is relocatable to the user's real Prism instance path, or constrain generation/distribution to an identical path tuple;
3. obtain a valid same-environment stock-vs-CDS A/B including pre-launch gate overhead, then require a physical Windows/laptop confirmation because archive mapping, filesystem/page-cache and path behavior are hardware/OS-sensitive.

Immediate NO-GO conditions are any Java/JVMTI agent requiring `AllowArchivingWithJavaAgent`, unsupported module-path flags, path relocation failure for the intended distribution model, inability to strong-fingerprint all transform-affecting inputs, changed Mixin/ModLauncher callback/failure behavior, or no coherent end-to-end gain.

This is deliberately a packaging/launcher experiment, not a BootOptim runtime cache.

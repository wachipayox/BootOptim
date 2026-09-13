# Distributable AppCDS / Prism relocation audit — 2026-09-13

Status: **NO-GO FOR PRE-GENERATED DISTRIBUTABLE ARCHIVE**

Agent 147 reopened only the packaged AppCDS line from draft PRs #260 and #266. The new question was whether the already-proven production-JAR hosted archive could be turned into a stable artifact that a Prism/Windows user can consume after installing the same modpack at an arbitrary absolute path, without changing Java, the OS, or Prism manually.

The answer is **no for a pre-generated `.jsa` distributed with the pack**. HotSpot's class-path identity requirement and Prism's absolute production class path make relocation across Prism roots a hard incompatibility. `-Xshare:auto` gives the required fail-open behavior, but after relocation it means "archive ignored, stock launch" rather than a usable portable optimization.

No runtime code, launcher mutation, archive generation, or performance A/B is added by this research.

## Prior state: #260 and #266

- PR #260 (`9bc246d098fe37c65bcdd0be3c8d887de54b061b`) remains open and draft. It established a strict identity/fail-open design and explicitly left production Prism/Windows relocation as a hard promotion gate. It did not enable CDS for users or claim savings.
- PR #266 (`b0859e461e8dfff02a06e687ead7caf5df654384`) remains open and draft. It added a Linux production-JAR surrogate, not Prism/Windows integration. Its successful workflow run `34617296050` generated a temporary 286,916,608-byte dynamic archive, consumed it in a separate fresh Java process, observed 4,641 application/custom-runtime shared-class hits, preserved the exact 14/14 resource-pack order with `reload_count=1`, and proved stale identity fails open. The `.jsa` was deleted before artifact upload.
- Neither PR is in `agent/integration-current@b3f0c5f6462a359483883741ac16d2f154868900`. This is intentional: both PRs are diagnostic/tooling drafts and their own descriptions say the Windows/Prism/relocation gate is still unresolved.

The hosted result is therefore a functional feasibility proof for NeoForge/ModLauncher/JarJar/custom-loader sharing on the same Linux/JDK/classpath tuple. It is not evidence that one archive can be distributed to a different Windows/Prism installation.

## Java 21 and Java 25 archive contract

Oracle's Java 21 tool specification documents the relevant AppCDS restrictions:

1. application archives are tied to a specific JDK build;
2. neither the class path nor module path may contain non-empty directories for application archiving;
3. the class path used when creating the archive must be the same as, or a prefix of, the runtime class path;
4. an archive cannot be loaded if class-path/module-path JARs have changed;
5. `--upgrade-module-path`, `--patch-module`, or `--limit-modules` disable CDS;
6. `-Xshare:on` is testing-only, while production fallback uses `-Xshare:auto`.

Java 25 retains the same production `-Xshare:auto` semantics and custom-loader AppCDS support. Java 25 also has newer AOT-cache facilities, but they are a different mechanism and are outside this AppCDS-only investigation.

For BootOptim, a distributable archive would still need a stronger outer identity than HotSpot's built-in checks because NeoForge discovers mod/JarJar inputs that are not represented solely by the initial Java class path. The minimum identity remains: exact JDK build/vendor/runtime and Java executable, OS/architecture, ordered production class path, launcher component/NewLaunch bytes, all transform-affecting and mod JARs (including parent JarJar containers), relevant JVM flags, pack fingerprint, archive hash/size, and absence of unsupported Java/JVMTI agents.

## Prism makes the production class path absolute

Current Prism source closes the relocation question.

`LauncherPartLaunch::executeTask()` obtains the absolute path to `NewLaunch.jar`, obtains the instance class path, prepends launcher JARs, then passes that complete list directly as the JVM `-cp` argument before starting Java.

`MinecraftInstance::getClassPath()` delegates to `LaunchProfile::getLibraryFiles(...)`. Its local-library root comes from `getLocalLibraryPath()`, which is explicitly `QDir(...).absolutePath()`. `LaunchProfile` also uses absolute paths for the synthetic/main JAR case. The production Java class path is therefore rooted in the actual Prism/instance installation path, not a relocation-stable relative representation.

Consequently, moving an otherwise byte-identical instance from, for example,

```text
C:\PrismA\instances\Pack\...
```

to

```text
D:\Games\Prism\instances\Pack\...
```

changes the class-path strings presented to HotSpot. A `.jsa` dumped under the first tuple does not satisfy the documented "same (or prefix) class path" contract under the second tuple. BootOptim cannot repair that from inside the Minecraft mod because CDS selection happens before the mod is loaded.

A producer could create an archive with a relative class path and move the whole directory while keeping the same relative strings, but Prism does not launch this pack that way. Making Prism emit relative class paths would require changing the launcher itself or interposing a wrapper that owns the final Java command, both outside the allowed delivery contract.

## Why `-Xshare:auto` does not rescue distribution

`-Xshare:auto` is still mandatory for any future candidate because it lets an unusable archive fall back instead of aborting the game. However, fail-open is a compatibility property, not relocation support.

For a pre-generated archive shipped to arbitrary Prism roots:

- matching producer path: archive may be usable if every other identity gate matches;
- different consumer path: HotSpot rejects/ignores the application archive and the game launches stock;
- therefore the shipped `.jsa` is not a stable optimization artifact for normal relocation.

BootOptim must not hide CDS rejection diagnostics. A future launcher gate may decide not to pass the archive after its own mismatch detection, but diagnostic validation must retain HotSpot CDS logging when proving archive use or rejection.

## Delivery without manual launcher edits

PR #260 proposed a Prism instance `PreLaunchCommand` plus a stable JVM `@argfile`. Prism does support per-instance JVM args and pre-launch commands, and Prism-compatible exported ZIP instances can carry `instance.cfg`; this can avoid asking the user to type JVM flags manually when the pack distribution itself owns the complete Prism instance.

That does not solve pre-generated archive relocation. It also is not a launcher-neutral modpack mechanism: a normal NeoForge mod cannot add JVM options to the already-running process, and a CurseForge/Modrinth-style pack does not universally control every launcher's pre-JVM command surface.

Current Prism 11.1.0 also has an open Windows custom-command path/quoting bug report (#6034, September 2026) involving special characters in paths. A production gate could potentially harden its quoting for known Prism versions, but relying on this surface would require its own version-pinned Windows validation. It is an additional delivery risk, not the primary NO-GO reason.

## Alternative premise: generate locally, do not distribute a `.jsa`

A **locally generated per-install archive** is materially different and remains a possible future research direction:

- first launch/training uses the user's actual Java build, OS, architecture, Prism root, absolute class path and exact pack;
- a later launch can consume only that locally generated archive after the same strict identity gate;
- relocation or Java/pack changes invalidate it and fall back to stock; a new local archive could then be generated for the new tuple.

This avoids the pre-generated relocation contradiction, but it is **not the distributable-archive mechanism requested here**. It also reopens design questions deliberately excluded from #260, including who is allowed to mutate the per-instance cache, how the first-run/training path is armed before the JVM starts, how Prism settings are provisioned without unsafe self-editing, and whether `AutoCreateSharedArchive` or an explicit `ArchiveClassesAtExit` lifecycle is acceptable. Do not silently reinterpret this NO-GO as approval to ship an auto-mutating archive cache.

Reopening criterion: a separate task may evaluate a local-generation/provisioning mechanism if it is explicitly authorized and can be installed as part of a Prism-compatible instance without editing the user's Java/system, without BootOptim mutating live Prism configuration, and with an exact invalidation/fail-open contract.

## NeoForge / ModLauncher compatibility result retained

Nothing in this relocation audit overturns #266's positive compatibility result. The hosted production-JAR harness proved that a same-tuple dynamic archive can contain and reuse thousands of application/custom-loader classes while still reaching the expected menu/resource contract. AppCDS custom-loader support means ModLauncher/Mixin/NeoForge are not categorically incompatible.

The failure is packaging portability: the archive is bound to a launch tuple that includes the production Java class path, while Prism encodes installation-specific absolute paths in that class path.

## Measurement and CI disposition

No new performance measurement is valid or needed for this decision. The blocking condition happens before a meaningful Windows A/B: a pre-generated archive cannot be assumed to load after relocation.

Existing evidence remains:

- origin: hosted Ubuntu 22.04 production-JAR surrogate, Oracle Java 25.0.4;
- workflow: `AppCDS production JAR-only harness` run `34617296050` (success);
- endpoint: both archive generation and fresh-process consumption reached BootOptim's existing main-menu validation; exact resource selection 14/14 and one reload;
- mechanism: 4,641 application/custom-runtime shared-class hits on consumption;
- performance: **no A/B and no savings claim**.

A Windows hosted client run is not required to prove the path contradiction because the Java requirement plus Prism's absolute class-path construction is source-level deterministic. The existing exact-pack documentation also records that GitHub Windows runners cannot provide a usable stock client GLFW window, so a Windows hosted menu-equivalence A/B would not be a valid replacement for a physical gate anyway.

## Decision

**NO-GO: do not ship a pre-generated AppCDS `.jsa` as a relocatable BootOptim/modpack artifact for Prism.**

Reasons:

1. Java 21/25 bind application archives to the archive-creation class path (same/prefix at runtime) and JDK build.
2. Prism's production Java class path is composed from absolute launcher/library/instance paths.
3. A normal mod cannot rewrite those VM startup arguments before its own JVM begins.
4. `-Xshare:auto` provides safe fallback but turns a relocated archive into an unused artifact, not a portable optimization.
5. #266 proves same-tuple AppCDS functionality, not relocation or user-deliverability.

Do not add production BootOptim code, ship a `.jsa`, modify Java, modify global caches, or ask the user to edit Prism/Java manually for this mechanism.

## References

- BootOptim PR #260: https://github.com/wachipayox/BootOptim/pull/260
- BootOptim PR #266: https://github.com/wachipayox/BootOptim/pull/266
- #266 successful harness run: https://github.com/wachipayox/BootOptim/actions/runs/34617296050
- Oracle Java 21 `java` tool / AppCDS: https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html
- Oracle Java 25 CDS guide: https://docs.oracle.com/en/java/javase/25/vm/class-data-sharing.html
- Prism `LauncherPartLaunch.cpp`: https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/launch/LauncherPartLaunch.cpp
- Prism `MinecraftInstance.cpp`: https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/MinecraftInstance.cpp
- Prism `LaunchProfile.cpp`: https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/LaunchProfile.cpp
- Prism current custom-command path issue #6034: https://github.com/PrismLauncher/PrismLauncher/issues/6034

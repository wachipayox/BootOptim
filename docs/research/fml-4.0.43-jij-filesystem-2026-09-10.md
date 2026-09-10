# FML 4.0.43 virtual JiJ discovery — 2026-09-10

Status: **PROFILED / COPY+SHA SIDECAR PREMISE REJECTED FOR 4.0.43**

PR: #244. Follow-up/correction to the JarJar paragraph in #239; interpreted alongside #241's closure-safe scaling evidence.

## Target and prior premise

Hosted exact-pack profiling in #239 left most dependency-discovery wall outside the ordinary mod-file reader and identified JarJar as one possible owner-specific follow-up. The follow-up premise was that pinned FML 4.0.43 copied each selected embedded JAR to a temporary file while hashing it, then checked a content-addressed output, which could make a strong-parent/relative-path -> child-digest sidecar a safe warm-residual candidate.

That premise is false for the exact runtime. The exact pack runs named module `fml_loader@4.0.43`. Source at the 4.x implementation immediately before the FML 5.0 version bump, and runtime method matching in #244, show that 4.0.43 uses the older virtual `jij:` filesystem implementation. It has `loadModFileFrom(IModFile, Path, IDiscoveryPipeline)`, not the later four-argument method, and has no `extractEmbeddedJarFile` method.

The authoritative 4.0.43 lifecycle is:

`file.findResource(path)` -> normalized `jij:` URI -> `FileSystems.newFileSystem(...)` -> fresh `JarContents.of(zipFS.getPath("/"))` -> `pipeline.readModFile(...ModFileDiscoveryAttributes.DEFAULT.withParent(file))`.

There is no stock temporary extraction, SHA-256 content-addressed target, move-into-place, or existing-output lookup in this implementation. Therefore a cold-vs-existing content-addressed-cache experiment would manufacture behavior that stock 4.0.43 does not have.

## Diagnostic method

PR #244 adds an isolated profile-only `premain` agent pinned fail-closed to named module `fml_loader@4.0.43`. It instruments only `JarInJarDependencyLocator.scanMods(2)` and `loadModFileFrom(3)`. The timed JVM records monotonic timestamps and string/primitive path data only. It does not retain/reuse `IModFile`, `JarContents`, `FileSystem`, reader, callback, or module objects and does not change JarSelector ordering, reader invocation, exception ordering, or `pipeline.addModFile` behavior.

Strong provenance and byte sizes are reconstructed after Minecraft exits. Physical parents are SHA-256 hashed post-process. Synthetic root `union:` runtime paths are decoded only far enough to recover their physical JAR; nested `jij:` parents are resolved through the previously reconstructed embedded payload chain. Each measured load therefore has `parent SHA-256 + embedded relative path -> child SHA-256 + child bytes` without adding full-parent hashing or embedded-byte reads to the measured discovery wall.

The analyzer fails closed on FML identity/implementation mismatch, missing scan/load events, any `extract_sha256` event, stock callback failures, unresolved provenance, or unreadable embedded entries.

## Hosted exact-pack result

Dedicated workflow run `34503549452` at head `f1b2d843948009ddc21c5c011ee92a89b9daccf7` passed. Measurement origin is **hosted exact-pack** (`exact-pack-2026-09-02-v1`, fixture SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`), fresh JVM, endpoint `main_menu`.

Runtime contract:

- main menu: **72,808 ms**;
- BootOptim Mixin errors: **0**;
- resource-selection check: **valid**;
- effective resource reload count: **1**.

Discovery/JarJar attribution from the same run:

- dependency discovery inclusive wall: **5,739.074 ms**;
- JarJar `scanMods` union wall: **1,928.169 ms** across **2** calls, **33.60%** of dependency discovery;
- stock `loadModFileFrom(3)` union wall: **1,838.520 ms** across **308** calls, **32.04%** of dependency discovery and **95.35%** of JarJar scan wall;
- reconstructed embedded payload volume across those calls: **327,155,458 bytes / 312.00 MiB**;
- unique strong provenance keys: **169**.

The largest individual measured intervals were repeated loads of `META-INF/jarjar/rocksdbjni-9.7.3.jar` (67.56 MiB) at **408.438 ms** and **286.746 ms**, followed by `aeronautics-neoforge-1.21.1-1.3.0.jar` (24.43 MiB) at **70.189 ms**. These are single hosted-run inclusive subintervals, not savings estimates. Repeated provenance keys likewise do not prove removable work; stock selection/lifecycle and possible repeated discovery passes still have to be preserved.

`scanMods` is nested inside dependency discovery, and `loadModFileFrom` is nested inside `scanMods`; these values must not be added together. This run is diagnostic only, not candidate/control A/B and not physical-laptop evidence.

## Decision

**Reject the copy+SHA digest-sidecar mechanism for exact `fml_loader@4.0.43`: the work and cache lookup it was meant to bypass do not exist in this runtime.** The corresponding future-candidate paragraph in #239 is superseded for the current exact pack.

The actual virtual-`jij:` path is material enough to remain a profiled subsystem (1.928 s inclusive JarJar wall on this hosted run), but #244 does not establish a safe optimization. In particular, 308 load calls / 312 MiB are not by themselves permission to cache readers, reuse `FileSystem`/`JarContents`, skip `pipeline.readModFile`, collapse duplicate-looking calls, or alter JarSelector/discovery ordering. #241 independently establishes that dependency discovery scales structurally with pack closure, but its complement deltas cannot be subtracted into JarJar savings.

No production code is proposed or promoted from this diagnostic.

## Reopening criteria

Reopen the **copy+SHA sidecar** idea only if BootOptim's exact runtime upgrades to an FML version whose pinned source/runtime actually contains the content-addressed extractor/check. That must be treated as a new version-specific investigation, with strong parent-content identity and fresh reader/runtime object reconstruction rather than reuse of the existing cheap scan-cache identity.

Reopen optimization of the **current 4.0.43 virtual `jij:` implementation** only with narrower causal evidence identifying removable work inside `loadModFileFrom` while preserving fresh `FileSystem`/`JarContents`, reader callbacks, parent attributes, selection/order, failure behavior, and lifecycle—or with a version-pinned upstream change that provides an equivalent safer boundary. Do not infer an optimization from call counts or repeated child digests alone.

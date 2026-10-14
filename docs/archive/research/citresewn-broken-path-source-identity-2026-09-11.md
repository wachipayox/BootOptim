# CITResewn broken-path direct-fix source identity — 2026-09-11

**Status: REJECTED / NO-GO pending exact corresponding source**

## Scope

PR #264 attributed all 128 repeated `PathPackResources Invalid path ''` errors in the pinned exact pack to `CITResewn.citresewn$brokenpaths$parseMetadata` probing `PathPackResources#listResources(..., path="", ...)`. Its source-equivalent diagnostic proved that omitting only that empty probe for `PathPackResources` changes the count from 128/128 to 0/128 while preserving resource selection 14/14 in order, `reload_count=1`, the two 8192x8192 atlases, zero Mixin errors, and main-menu completion. The diagnostic deliberately did not filter logs and did not claim timing savings.

This follow-up asks a different question: can that source-equivalent change be made directly in the exact CITResewn lineage used by the pack, with clear source provenance and redistribution rights?

## Exact-pack binary identity

The pinned fixture remains:

- release: `exact-pack-2026-09-02-v1`
- asset: `bootoptim-exact-pack.zip`
- fixture SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`
- CITResewn entry: `mods/(svfr) CitResewn {v0} [1.21.1] [MAINLOC].jar`

A hosted diagnostic on this branch extracted that one JAR from the already pinned fixture only to hash/list archive metadata; it did **not** decompile classes or reconstruct source. Run `34622165418` completed successfully and recorded:

- exact-pack CITResewn size: `203957` bytes
- exact-pack CITResewn SHA-256: `315b46f2a78d5298426fb594558873ac19abbc499f77578f6cd4f98e6809e6a9`
- exact-pack CITResewn SHA-512: `ff29d9ef06f5163b082f7914425486670eeb179c2cc5b49b3fd6f95e8328d4ab671bac88889a8b35b49a7bf421a85f30ad1e8d9d28cd4f1820e99b9f1638eadc`
- relevant class entry: `schm/shsupercm/citresewn/mixin/AbstractFileResourcePackMixin.class`
- mixin config: `citresewn.mixins.json`
- no top-level or nested `LICENSE` entry was found by the narrow archive-entry check used in this diagnostic

The package spelling is material: the deployed JAR uses `schm.shsupercm.citresewn`, matching the historical target shape already used by BootOptim compatibility code, not the public upstream package `shcm.shsupercm.fabric.citresewn` and not the newer NeoForge fork package `com.github.citresewn`.

## Public source candidates and licenses

### Archived upstream: `SHsuperCM/CITResewn`

The archived upstream is public and MIT-licensed (`Copyright (c) 2021 SHsuperCM`). Its public v1.2.2 source bump is commit `47e7cb861db09744880c69f41f989b1904d63d7f`. At that commit the broken-path mixin is source-visible at:

`src/main/java/shcm/shsupercm/fabric/citresewn/mixin/broken_paths/AbstractFileResourcePackMixin.java`

It performs the same semantic empty-prefix probe via `findResources(ResourceType.CLIENT_RESOURCES, namespace, "", emptyCallback)` and catches `InvalidIdentifierException` to publish broken-pack compatibility metadata.

The official Modrinth release for Minecraft 1.21/1.21.1 is `1.2.2+1.21` (version id `JUnP9V1A`) and publishes both a binary JAR and sources JAR. The primary binary has:

- filename: `citresewn-1.2.2+1.21.jar`
- size: `396723` bytes
- SHA-1: `5569769f66eda6d51ca3ae56d58d5a4657f19ef9`
- SHA-512: `24338b35423798b3d842025d2a8725b980980b7ccb168b876e21ea1d4067ecba3f67918b0d6b8b332128841ab6cbe2c2d3fd408c0dd36eeeb36cb76a875c442a`

This is **not** the exact-pack binary: both size and SHA-512 differ, and its source package is `shcm.shsupercm.fabric.citresewn` rather than the deployed `schm.shsupercm.citresewn`.

### Public NeoForge fork: `CancriRecoleta/CITResewn`

The 2026 public NeoForge fork is also source-visible. Current commit `8cca0f127f3898472e2109f6ac6a797253756893` declares version 0.7.22 and contains the equivalent empty probe in:

`src/main/java/com/github/citresewn/mixin/broken_paths/AbstractFileResourcePackMixin.java`

using `listResources(PackType.CLIENT_RESOURCES, namespace, "", emptyCallback)`. Its repository license text grants LGPL v3-or-later terms and identifies a 2026 copyright holder. GitHub records the repository as a separate public repository created in June 2026, not as the archived upstream repository, and the package is `com.github.citresewn`.

That source is therefore useful semantic evidence for the bug but is **not proven corresponding source** for the exact-pack JAR.

### Other public forks / NeoForge patchers

A GitHub repository search also found numerous CITResewn forks and `HiWord9/CITResewnNeoPatcher`, whose tree contains mapped copies of the official 1.2.2+1.21 artifacts. Searches for the deployed package spelling `schm.shsupercm.citresewn` and the corresponding source path returned no public source match among the identified candidates. No claim is made that every private or deleted repository was searched.

## Why direct implementation stops here

The minimum source edit itself is well bounded by #264: preserve the `broken_paths` metadata logic and namespace loop, but do not call the empty-prefix enumeration probe for the path-backed pack implementation for which stock rejects `""` before enumeration/callback. ZIP-backed and other pack implementations must retain their existing probe, exception handling, metadata result, and warning/error surfaces.

However, this task explicitly forbids distributing or replacing the JAR unless source/version correspondence and license are clear. The exact binary is not the official 1.2.2+1.21 release, does not match the public 0.7.22 fork identity, uses a third package spelling, and the narrow archive metadata check did not find an embedded license. Assigning either MIT or LGPL terms to that modified binary would therefore be unsupported.

Accordingly:

- no CITResewn source was reconstructed from bytecode;
- no decompilation was performed for this source-identity decision;
- no forked/rebuilt CITResewn JAR is published or substituted into the exact pack;
- no BootOptim external workaround is promoted; #264 already rejected that ownership model;
- no timing saving is claimed from removal of 128 log lines.

## Correctness contract for reopening

Reopen direct implementation only when the exact JAR can be linked to public or otherwise authorized corresponding source by a strong provenance chain, for example an exact published binary hash, a reproducible build from a pinned source commit that explains the deployed package/transform, or maintainer-provided source plus license for the exact fork.

Once that identity exists, the direct fork must pass all of the following before promotion:

1. **Version/source pin:** source commit, build toolchain, mappings/loader transform (if any), output filename, and output hash are recorded.
2. **License gate:** the exact fork's redistribution/modification license is explicit and its notices/source obligations are satisfied.
3. **Narrow code test:** path-backed packs skip only the empty-prefix probe; non-empty paths and non-path-backed packs are unchanged.
4. **`broken_paths` preservation:** an intentionally invalid/broken resource path still triggers the same compatibility metadata/result that the original mixin intends to provide.
5. **Valid metadata preservation:** normal pack metadata is byte/field-equivalent through the patched path.
6. **Error-surface test:** a distinct `listResources`/metadata failure remains observable; no logger filter, broad exception suppression, or diagnostic deduplication is introduced.
7. **Exact-pack smoke:** 14/14 resource selection in identical order, `reload_count=1`, expected atlas dimensions/count, zero Mixin errors, and main-menu completion.
8. **Target diagnostic:** the exact `PathPackResources Invalid path ''` family changes from 128 to 0 while unrelated warnings/errors are unchanged or explained individually.

A timing A/B is not a promotion prerequisite for this correctness fix and must not be inferred from the diagnostic count reduction.

## Decision

**NO-GO for direct fork/replacement on the evidence currently available.** The source-level fix is technically narrow and #264 already validates its runtime semantics, but the exact pack binary cannot presently be tied to a public/licensed corresponding source. Promotion is blocked on provenance/license, not on the mechanics of the fix.

Related evidence: PR #151, PR #223, PR #257, PR #264; hosted identity run `34622165418`.

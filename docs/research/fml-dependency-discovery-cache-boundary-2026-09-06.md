# FML dependency-discovery persistence boundary — 2026-09-06

Status: **production hypothesis rejected; no runtime change proposed**.

This note records Agent 27's review of the early NeoForge/FML path for the exact BootOptim pack. The single prioritized hypothesis was a persistent warm-start cache for work inside dependency discovery that is *not* already covered by BootOptim's production `ModFile.compileContent()` scan cache.

## Baseline and scope

The prompt named `agent/integration-current` at `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`, but GitHub showed that ref had moved. The authoritative integration head at the time of this review was:

- `agent/integration-current` = `145c10c2f8132b21e7b7be067c56513b394ccb5a`
- `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c` is an ancestor, 15 commits behind.

All BootOptim source conclusions below use `145c10c2f8132b21e7b7be067c56513b394ccb5a`.

The exact project configuration is Minecraft 1.21.1 + NeoForge 21.1.248 (`gradle.properties`). Public NeoForged FancyModLoader 1.21.1 source was inspected at commit `15c77cf658f360c171668a8700d02c30ad0cd965`, the last 1.21.1 branch commit before the 21.1.248 release window. The relevant `ModDiscoverer.java` and `JarInJarDependencyLocator.java` blobs are unchanged at the later 1.21.1 branch head inspected during this review, so the control-flow observations do not depend on a later source edit.

This review deliberately does not reopen:

- PR #99's failed per-ModContainer registry attribution (`mod_count=0`);
- generic Mixin side-load caching rejected by #43;
- the small ClassInfo negative-cache result from #46;
- the post-Mixin ASM writer tail from #48;
- generic class prewarm, which regressed in #19;
- the production metadata scan cache already implemented by #5/#11 and present in current integration.

## Prioritized hypothesis

> Persist the result of dependency discovery (especially JarJar metadata selection) across launches, version/fingerprint it strongly, and reuse it on warm starts so FML avoids repeating expensive nested dependency resolution before module-layer setup.

This was prioritized because the only directly measured early-loader ceiling is materially larger than the already rejected Mixin micro-caches.

Existing hosted exact-pack evidence recorded by PR #99 from a PR #96 smoke:

- root discovery: **476.726 ms wall**;
- dependency discovery: **6,445.528 ms wall**;
- scan cache: **240 misses / 0 hits**;
- `compileContent()` scan window: about **3,591.595 ms wall**.

Those numbers are **cold-fixture inclusive wall measurements**, not a warm-cache saving estimate. Subtracting the scan window from dependency discovery gives 2,853.933 ms arithmetically, but that is not an exclusive attribution and must not be treated as recoverable wall or TTMM.

A later PR #99 diagnostic run (`33930066449`, exact-pack result artifact `9958281257`) reached the menu in 67,684 ms with 0 BootOptim Mixin errors. That run was useful for lifecycle/registry attribution, but it was not a controlled two-launch warm-discovery experiment and therefore does not establish a reusable dependency-discovery saving.

## What current BootOptim already caches

`bootstrap/src/main/java/dev/wachipayox/bootoptim/bootstrap/CachingModFileReader.java` is production code. Its `CachedModFile.compileContent()` override changes only class-metadata scanning for regular files:

- hit: restores `SecureJar.Status` and serialized `ModFileScanData`;
- miss/failure: executes stock `ModFile.compileContent()`;
- persistence is optional and asynchronous;
- `-Dboot_optim.scanCache=false` is the kill switch;
- cache directory: `.bootoptim/mod-scan-cache-v1`.

Its current identity contains file name, size, mtime, file key, FML implementation version, BootOptim version, Java feature version and schema version. That is the contract of the existing scan cache; it is **not** sufficient justification for a new cache that would suppress dependency resolution, module construction, transformations or callbacks. Any such new cache would need a stronger content/semantic fingerprint appropriate to the result it replaces.

The existing cache therefore does not cover the product considered here: dependency-locator decisions, nested-JAR selection, nested `FileSystem`/`JarContents` construction, reader invocation, discovery attributes, unique-list processing, language identification, access-transformer registration, mixin config registration, enum extension loading, module-layer resources, or class transformation.

## Exact FML boundary

### `ModDiscoverer.discoverMods()`

Source:
`net.neoforged.fml.loading.moddiscovery.ModDiscoverer#discoverMods`

After root candidate discovery and a first `UniqueModListBuilder`, FML iterates **every** `IDependencyLocator` loaded through `ServiceLoaderUtil`:

```java
for (var locator : dependencyLocators) {
    var pipeline = new DiscoveryPipeline(
        ModFileDiscoveryAttributes.DEFAULT.withDependencyLocator(locator),
        loadedFiles,
        discoveryIssues);
    locator.scanMods(List.copyOf(loadedFiles), pipeline);
}
```

It then runs `UniqueModListBuilder` again and creates a `ModValidator`.

This means the dependency-discovery result is not defined by FML/JarJar alone. `IDependencyLocator` is a public SPI loaded through ServiceLoader. Its contract permits a locator to inspect the complete currently loaded mod list and emit arbitrary candidate `IModFile`s through `IDiscoveryPipeline`.

A cache placed around the aggregate loop would therefore suppress third-party locator execution. Correct invalidation would have to capture not just all mod bytes, but also every installed locator implementation and every environmental/configuration input those locators are allowed to consult. FML's SPI provides no declaration of those inputs. There is no maintainable complete fingerprint for this aggregate product.

### `JarInJarDependencyLocator.scanMods()`

Source:
`net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator#scanMods`

The stock locator delegates to:

```java
JarSelector.detectAndSelect(
    loadedMods,
    this::loadResourceFromModFile,
    (file, path) -> loadModFileFrom(file, path, pipeline),
    this::identifyMod,
    this::exception);
```

`loadResourceFromModFile()` opens dependency metadata from each mod through the mod file abstraction. For every selected nested dependency, `loadModFileFrom()` then:

1. resolves the nested path from the parent `IModFile`;
2. creates a `jij:` filesystem;
3. builds fresh `JarContents`;
4. calls `pipeline.readModFile(jar, DEFAULT.withParent(file))`;
5. lets the normal reader chain construct the nested `IModFile`.

This is the critical semantic boundary. A safe cache of *selection metadata* can at most avoid re-reading/parsing JarJar metadata and recomputing the version selection. It cannot safely reuse live `IModFile`, `SecureJar`, `FileSystem`, parent/discovery-attribute state or reader side effects across processes.

To preserve FML behavior, a warm hit would still need to create the nested filesystem/JarContents and run the current reader chain for selected dependencies. That leaves a substantial fraction of the suspected work untouched. Caching deeper than this crosses into lifecycle-bearing objects and is rejected.

### Validation and transformation setup are not safe cache targets

`FMLLoader.beginModScan()` constructs `ModDiscoverer` and calls `discoverMods()`.

`FMLLoader.completeScan()` then builds the language-provider loader and calls `ModValidator.stage2Validation()`.

`ModValidator.stage2Validation()` performs, in order:

- language validation (`ModFile.identifyLanguage()`);
- `ModSorter.sort(...)`;
- `LoadingModList.addAccessTransformers()`;
- `LoadingModList.addMixinConfigs()`;
- `LoadingModList.addEnumExtenders()`;
- creation/submission of `BackgroundScanHandler` work.

`LoadingModList.addAccessTransformers()` calls `FMLLoader.addAccessTransformer(...)`, which loads each AT into the active access-transformer engine. `addMixinConfigs()` conditionally registers configs based on the final loaded-mod set. Enum extenders likewise resolve current mod resources.

These are observable loader operations, not pure metadata decoding. Persisting their post-state would require complete identities for FML/ModLauncher, transformation plugins, mod and nested-mod bytecode, AT files, mixin configs/plugins, side/distribution, Java runtime and all relevant dynamic configuration. More importantly, restoring a serialized result would bypass third-party callbacks/registration against fresh loader state. No supported FML API exposes such a cache boundary.

The same objection applies further downstream to module/classpath setup and transformed-class persistence. Module resources are constructed from current `SecureJar`s, and class transformation can depend on access transformers, Mixin config plugins and other launch plugins. The project already rejected broader transformed-class caching because no supported ModLauncher boundary proves those dynamic inputs or offers a safe replacement hook.

## Identity contract required for a narrower JarJar cache

A hypothetical cache limited strictly to `JarSelector.detectAndSelect` would still need, at minimum:

- content hash of every root mod participating in selection, not just filename/mtime;
- content hash of every relevant JarJar metadata resource and selected nested payload;
- exact ordered set and version/hash of FML/JarJar selector code;
- exact ordered root-mod identity set, because version resolution is global;
- schema version and BootOptim implementation version;
- any flags that change candidate eligibility/reader behavior.

It must **not** cache `IModFile`, `SecureJar`, `FileSystem`, discovery attributes, AT engine state, mixin registration state, module layers or transformed bytecode.

Computing complete outer/nested hashes every launch also adds storage I/O. A two-tier identity (cheap stat key backed by a remembered content digest) can reduce hashing only by trusting filesystem metadata, which is weaker than the complete fingerprint required for a cache that changes loader decisions. Therefore the integrity requirement erodes the expected win before any exact-pack benefit has been demonstrated.

## Decision

**Reject production implementation of a persistent dependency-discovery/JarJar-result cache on the current evidence.**

Reasons, in priority order:

1. **No warm critical-path proof.** The 6.445 s measurement is cold and contains 240 scan-cache misses plus a ~3.592 s compileContent window. No controlled warm run shows a material residual to recover.
2. **Aggregate discovery has no complete semantic fingerprint.** `IDependencyLocator` is an extensible ServiceLoader SPI; arbitrary third-party locators may depend on undeclared runtime/configuration state.
3. **The narrow pure product has a low/unknown ceiling.** Caching only JarJar metadata selection still requires fresh nested filesystems, JarContents, reader execution, validation and all transformation registration.
4. **Deeper caching crosses lifecycle state.** Reusing readers, `IModFile`/SecureJar/module products, AT state or transformed bytecode would suppress or stale third-party behavior and violates BootOptim's compatibility contract.
5. **Fingerprint cost is non-zero.** A defensible content-complete identity requires hashing exactly the inputs whose I/O the optimization hopes to avoid.

No kill switch or runtime branch was implemented because there is no production candidate to gate. The research branch is documentation-only and does not alter startup behavior.

## Reopening criterion

Do not reopen this hypothesis from the cold 6.445 s figure.

A future agent may reopen it only after a **controlled two-launch hosted exact-pack diagnostic** that preserves `.bootoptim` between launches and reports, for the second launch:

- root/dependency discovery wall;
- scan-cache hit/miss/fallback counts;
- `compileContent()` wall separately;
- per-`IDependencyLocator` wall, or equivalent evidence that identifies the stock JarJar locator versus Connector/other locators;
- TTMM for both launches, with the second launch reaching the same usable-menu endpoint and zero BootOptim Mixin errors.

Only if the warm residual is material **and** stock `JarInJarDependencyLocator`/`JarSelector.detectAndSelect` owns a substantial exclusive portion should a new experiment be considered. That experiment must cache only a pure, serializable selection manifest, use full content fingerprints for all selection inputs, recreate normal nested `IModFile`s through the current reader pipeline, fail open on every mismatch/error, and have its own opt-out property.

If warm residual is instead dominated by a third-party locator, investigate that locator's own source and semantics rather than wrapping the aggregate FML loop.

## Related evidence

- Integration authority: https://github.com/wachipayox/BootOptim/commit/145c10c2f8132b21e7b7be067c56513b394ccb5a
- Existing scan cache: https://github.com/wachipayox/BootOptim/blob/145c10c2f8132b21e7b7be067c56513b394ccb5a/bootstrap/src/main/java/dev/wachipayox/bootoptim/bootstrap/CachingModFileReader.java
- PR #5: https://github.com/wachipayox/BootOptim/pull/5
- PR #11: https://github.com/wachipayox/BootOptim/pull/11
- PR #19: https://github.com/wachipayox/BootOptim/pull/19
- PR #43: https://github.com/wachipayox/BootOptim/pull/43
- PR #46: https://github.com/wachipayox/BootOptim/pull/46
- PR #48: https://github.com/wachipayox/BootOptim/pull/48
- PR #99: https://github.com/wachipayox/BootOptim/pull/99
- PR #99 exact-pack diagnostic run: https://github.com/wachipayox/BootOptim/actions/runs/33930066449
- PR #99 run artifact `9958281257`: https://github.com/wachipayox/BootOptim/actions/runs/33930066449/artifacts/9958281257
- FML exact-era `ModDiscoverer`: https://github.com/neoforged/FancyModLoader/blob/15c77cf658f360c171668a8700d02c30ad0cd965/loader/src/main/java/net/neoforged/fml/loading/moddiscovery/ModDiscoverer.java
- FML exact-era `JarInJarDependencyLocator`: https://github.com/neoforged/FancyModLoader/blob/15c77cf658f360c171668a8700d02c30ad0cd965/loader/src/main/java/net/neoforged/fml/loading/moddiscovery/locators/JarInJarDependencyLocator.java

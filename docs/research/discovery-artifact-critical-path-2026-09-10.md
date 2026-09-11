# FML discovery artifact critical-path diagnostic — Agent 106 — 2026-09-10

Status: **DIAGNOSTIC COMPLETE / PROFILE-ONLY / NO OPTIMIZATION**

## Scope and authority

Assignment authority is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`, Minecraft 1.21.1, NeoForge 21.1.248 and the exact-pack FML 4.0.43 line. This branch is intentionally stacked on diagnostic PR #202 (`c0f995a7afa5480f3eff91f18decb5fd9f5bb619`) so it can use the #200/#201 structured trace contract already validated in SERVICE. It must be rebased after that diagnostic stack is resolved. Nothing here is a production optimization.

The physical observation motivating this work is root discovery around 5–8 s and dependency discovery around 14–22 s. Hosted absolute time is not a laptop baseline.

## Historical closures honored

- The persistent class/annotation scan cache already exists in `CachingModFileReader`; this work does not add, widen, or reinterpret it. Historical #4/#5 established the cache, #11 moved persistence off the cold path, and #20/#21/#33 hardened invalidation/serialization.
- PR #134 closed a generic dependency/JarJar result cache: third-party dependency locators can depend on runtime/config state, and fresh `jij:` JarContents / normal reader processing have lifecycle semantics that are not represented by a generic input fingerprint.
- PR #185 measured BootOptim's wrapper-fallback scan and closed it as a production target. This work does not reopen that path.
- PR #202 demonstrated that directly transforming bootstrap-loaded FML `ModLoader` is unreliable in this launch topology. This diagnostic therefore does not patch `ModDiscoverer`, dependency locators, futures, callbacks, or discovery ordering.

## Exact source map

FancyModLoader `ModDiscoverer` performs root locator callbacks sequentially, constructs a `DiscoveryPipeline`, calls `JarContents.ofPaths`, and then iterates `IModFileReader`s for each artifact. After the first unique-list pass it invokes each `IDependencyLocator.scanMods` and feeds resulting artifacts through the reader pipeline.

One important provenance detail is source-level, not inferred from counters: stock `JarInJarDependencyLocator.loadModFileFrom` calls `pipeline.readModFile(jar, ModFileDiscoveryAttributes.DEFAULT.withParent(file))`. Unlike `addJarContent`, `readModFile` does not merge the pipeline's default dependency-locator attributes. Therefore the per-reader records for stock JarJar dependencies correctly carry a non-null parent and `fs=jij`, but `dependency_locator=none`. This is an FML 4.0.43 API-path property, not missing instrumentation.

BootOptim's production `CachingModFileReader` is already the highest-priority standard reader. Its accepted path probes `META-INF/neoforge.mods.toml`/manifest type and constructs a `ModFile`. Class/annotation `compileContent()` remains a separate later operation and retains the existing persistent scan-cache behavior.

## Instrumentation

The SERVICE registration is replaced on this diagnostic branch by `DiscoveryArtifactProfilingModFileReader`, a decorator around the exact production `CachingModFileReader`. With artifact detail disabled it is one delegate call and preserves the same reader decisions. With `bootTrace=profile|development` plus `-Dboot_optim.profileDiscoveryArtifacts=true` it emits one lexical `fml_discovery_artifact_metadata` task per real reader invocation.

Each task records artifact path and filesystem provider, physical size when meaningful, root/dependency locator attributes, parent artifact, accepted mod identity/count, and pinned FML/NeoForge identity. Sibling reader calls on a discovery thread are chained in actual invocation order. Root and dependency discovery are chained (`dependency_discovery` depends on completed `root_mod_discovery`). Parent task ids are used only when the reader executes lexically on the phase-owner thread. The analyzer unions overlap and never converts a sum of artifact walls into presumed savings.

### ZipFS / I/O / callback attribution

Direct instrumentation of JDK ZipFS or bootstrap FML is deliberately avoided. The same opt-in starts one bounded JFR recording at literal root-discovery begin and stops it at dependency-discovery end. FileRead/FileWrite (>=1 ms), ExecutionSample (10 ms), ThreadPark (>=2 ms), and JavaMonitorEnter (>=2 ms) include stacks.

The current implementation stops **and dumps** the recording synchronously at dependency-discovery end, before JVM teardown can invalidate the recording repository. It does not parse/consume JFR events while startup is timed. The already-dumped file is summarized from the shutdown hook, ranking thresholded physical I/O and classifying sampled stacks as metadata parse, dependency selection, ZipFS/ZipFile, SecureJar/JarContents, Connector callback, locator callback, class scan, or other. Sampling and aggregate blocking time are attribution evidence, never recoverable wall.

The existing `BOOTOPTIM_SCAN_CACHE ... elapsed_ms=` line remains an actual measured `compileContent()` span. Hit/miss/finished counters are not converted into TTMM savings.

## Hosted exact-pack validation

Instrumentation commit `5263ff82750dc7423043d8048f61ba642255ea11` passed all required gates:

- Build Actions `34492258406`: **success**.
- normal Startup Benchmark Actions `34492258465`: **success**.
- exact-pack profile smoke Actions `34492258418`: **success** with fixture `exact-pack-2026-09-02-v1`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.
- exact-pack reached `main_menu` at 93,403 ms, `bootoptim_mixin_errors=0`, one effective reload, and resource selection exactly matched the expected enabled pack order.

The structured trace is lossless and internally valid: schema v1, origin `hosted_exact_pack`, endpoint `main_menu`, 1,452 events, 485 task begins / 485 task ends, 482 resource-parse records, zero trace error records, `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`, valid lexical nesting, all dependency/parent references present, and no dependency cycle.

The analyzer's winning chain is `root_mod_discovery -> dependency_discovery -> fml_gather_and_initialize_mods`. Its union wall is 13,894.579 ms. That number is a causal union, not the sum of nested artifact tasks and not a savings claim. Discovery itself contributes 8,347.936 ms on the same chain: 708.262 ms root followed by 7,639.674 ms dependency discovery.

A preceding profile smoke at instrumentation commit `f9d22dc36a0445e884938e086b66a1a84a49cddb`, Actions `34491573546`, also passed the exact-pack runtime/resource gates and produced the same reader topology. Its JFR start/stop worked but the first implementation attempted to dump too late during teardown and failed with `FileNotFoundException`; that defect is what `5263ff8` fixes. The earlier run is used only as a repeated attribution observation, not as a performance comparator.

## Discovery decomposition and scaling

| Hosted profile | phase wall | reader metadata calls | measured reader wall | reader share | residual outside reader |
| --- | ---: | ---: | ---: | ---: | ---: |
| `f9d22dc` root | 549.156 ms | 173 | 164.646 ms | 29.98% | 384.510 ms |
| `f9d22dc` dependency | 5,939.486 ms | 309 | 86.216 ms | 1.45% | 5,853.269 ms |
| `5263ff8` root | 708.262 ms | 173 | 194.485 ms | 27.46% | 513.777 ms |
| `5263ff8` dependency | 7,639.674 ms | 309 | 123.978 ms | 1.62% | 7,515.696 ms |

The call counts above describe topology/coverage only. The measured wall demonstrates the important causal result: approximately 98.4% of the current hosted dependency-discovery window is **outside** BootOptim/FML standard reader metadata work. A general metadata or class-scan cache cannot explain the dominant residual.

Current-run filesystem mix was 167 `file`, five `jij`, and one `union` reader calls in root; all 309 dependency reader calls were `jij`. Cross-sectional correlation between reader duration and `log1p(physical size)` was only about 0.04 in root and 0.09 in dependency discovery, so there is no useful evidence that standard metadata parse scales materially with artifact size here. The largest individual reader scopes were about 26 ms for Colorwheel in root and 9.5 ms for nested `yumi-commons-event`; neither supports a per-mod metadata cache candidate.

The dominant scaling dimension is therefore locator-owned work outside the reader: dependency selection, embedded-JAR materialization/hash, Connector Fabric discovery/transformation/cache validation, and their filesystem/classpath consequences. One exact pack cannot establish a global slope, so this is a within-pack attribution only.

## Bounded JFR attribution on the valid current smoke

The classified 10 ms execution samples inside the root->dependency JFR window were:

| category | samples |
| --- | ---: |
| Connector callback / transform path | 341 |
| ZipFS / ZipFile | 74 |
| dependency selection | 32 |
| other | 22 |
| metadata parse | 19 |
| SecureJar / JarContents | 13 |
| locator callback (non-Connector bucket) | 3 |

Connector is therefore the dominant sampled CPU owner in this hosted discovery window. The sample count is not converted to milliseconds saved because work can overlap and samples include worker threads.

Thresholded I/O also names the exact pack's three Fabric-port candidates and their mapped outputs. Examples include `(fabric-port) MusicMakerMod...` with a ~9.86 MiB thresholded read and 15.958 ms I/O event duration, Furnish with ~2.65 MiB and 9.662 ms on the source plus 11.807 ms on its mapped output, and JoyOfPainting's mapped output with 94.601 ms accumulated thresholded I/O duration. These event durations are diagnostic lower-bound/shape evidence, not total artifact wall.

Aggregate JFR blocking reports 15,005.282 ms of parks and 8,217.115 ms of monitor-enter duration across all participating threads. Those totals overlap each other and the phase wall; they are not a critical-path or savings quantity.

## Source-confirmed redundant work / candidate boundaries

### 1. Sinytra Connector 2.0.0-beta.17+1.21.1 — primary follow-up owner, no duplicate transform cache

The exact pack uses Connector `2.0.0-beta.17+1.21.1`. Its exact source shows `ConnectorLocator.locateFabricMods` scanning Fabric mods, creating `TransformableJar`s, discovering nested jars, resolving dependencies, transforming/reusing outputs, merging split packages, and constructing fresh FML mod files. The current hosted log identifies three candidates to load, matching Furnish, JoyOfPainting and MusicMakerMod being rejected by ordinary root discovery and then handled by Connector.

Connector already owns a versioned transformed-JAR cache. BootOptim must not create a second general transform cache. However, exact Connector source exposes recurring warm validation work worth a future **warm residual diagnostic**: `JarTransformer.cacheTransformableJar` parses Fabric metadata, then `TransformerUtil.getCached` reads the entire input with `Files.readAllBytes` and SHA-256 hashes it before checking the `.input` sidecar/output, and afterwards computes the module name by reopening jar metadata. Thus even a transformed-output cache hit still performs full source hashing plus input-derived metadata/module work.

A future safe boundary, if and only if warm evidence proves it material, is an owner/version-specific immutable input-description sidecar: exact Connector/Adapter cache identity + strong input content digest + only demonstrably input-derived metadata/module descriptor. Fresh runtime FML objects, dependency resolution, split-package decisions, callbacks, transform-cache validation/failure behavior and publication must still execute. The unresolved economic problem is obtaining/trusting the strong content identity without simply paying the same full-file hash cost. **No implementation is justified by this cold hosted run.**

### 2. Stock FML JarJar extraction/digest — narrow source-level redundancy, not the hosted dominant owner

Pinned FML 4.0.43 source confirms a narrower redundancy than the generic result cache rejected in #134. For each selected embedded JAR, `JarInJarDependencyLocator` creates a temp file, copies the embedded bytes into it while computing SHA-256, derives the content-addressed final path from that digest, and only **afterward** checks whether the final cached file already exists. Therefore an already-materialized child is still recopied and rehashed each launch before reuse.

A theoretically safe future cache would store only the child content digest/size for a strong parent-content identity + embedded relative path + exact FML/JarJar schema/version. On a valid hit it could select the already content-addressed child, but it must still create fresh `JarContents`, invoke normal readers, preserve selection/errors/order and rebuild all runtime-bound state. BootOptim's existing scan-cache key (filename/size/mtime/fileKey + version fields) is not automatically a cryptographic parent-content identity and must not be repurposed as one without proof.

This source finding is real redundancy, but the current JFR has only 32 dependency-selection samples versus 341 Connector samples and does not establish a seconds-scale warm extraction/hash ceiling. **No optimization is implemented.**

### 3. Per-mod metadata parse, generic scan-cache widening, wrapper fallback

No current artifact has material reader metadata wall. A per-mod metadata cache, generic discovery cache, scan-cache widening, live ZipFS/JarContents reuse, or count-based claim is rejected by this profile. BootOptim wrapper-directory fallback remains closed by #185 and is not reopened.

## Decision

**Diagnostic complete; no production optimization is justified yet.** The root/dependency critical path is real and the dependency residual is material, but the current clean hosted fixture attributes its CPU primarily to Connector-owned Fabric discovery/transformation activity rather than FML metadata parsing. Connector already has a transform cache, so duplicating it in BootOptim would repeat a solved mechanism without warm evidence.

The highest-value future reopening is a Connector-specific warm-residual diagnostic that preserves its own cache and separates full-input SHA validation, Fabric metadata parse, module-descriptor read, dependency resolution, actual transform misses/hits, split-package work and callback wall. Second priority is the narrow stock JarJar parent-entry -> child-digest sidecar frontier, but only after warm evidence shows copy/hash is material. Both require exact owner/version fingerprints and must preserve fresh runtime objects/callbacks. No laptop run, A/B, scheduler rewrite, generic cache or production code is requested from this PR.

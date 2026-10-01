# Connector warm-residual attribution — 2026-09-10

## Scope

Agent 109 diagnostic only. Authority is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`; this branch is stacked on diagnostic PR #239 solely to reuse its already-gated discovery trace. No production optimization, generic cache, scheduler change, laptop run, or A/B was performed.

PR #239 established that hosted exact-pack dependency discovery is 5.9–7.6 s while the stock mod reader accounts for only about 1.5–1.6% of that wall, and its bounded JFR samples are dominated by Sinytra Connector. Connector `2.0.0-beta.17+1.21.1` already owns a transformed-JAR cache, so this diagnostic measures the residual work on that cache's warm path instead of adding another cache.

The upstream target is pinned to Connector `2.0.0-beta.17+1.21.1`, source commit `8b27f1ad042aae8037bcc522b321c03fcce1a12a`. The exact-pack copy is patched offline before ModLauncher starts. Shape/version/signature guards fail closed. The helper is relocated into `org.sinytra.connector.bootoptim` so the diagnostic does not introduce a JPMS split package with BootOptim's SERVICE module.

## Measurement contract

`run_connector_warm_profile.py` prepares one exact-pack directory, patches only its Connector JAR, then launches the identical instrumented pack once to populate Connector's existing `.cache/connector`. That prime process is setup only and is not a control or performance comparator. The harness verifies Connector sidecars exist, preserves that cache, clears runtime logs/crash/MCEF outputs, then launches the same pack a second time and measures that warm state.

The Connector trace records monotonic scopes plus structural parent and same-thread predecessor IDs. The analyzer validates parent containment / DAG references, unions overlapping intervals rather than summing nested tasks, and aligns Connector and BootOptim trace clocks to require the Connector dependency-locator callback to fall inside `dependency_discovery`.

Instrumented coarse boundaries: dependency-locator callback, `locateFabricMods`, Fabric candidate scan, Fabric metadata, transform-cache validation, `Files.readAllBytes`, SHA-256, module descriptor, dependency resolution, transform dispatch, split-package merge, fresh `IModFile` creation, Forge package filtering, embedded JarJar callback, and original-path callback. Cache hit/miss is observed from Connector's own returned `CacheFile`; no decision is replaced.

## Hosted exact-pack result

Fully gated runtime head: `fd34d2d143f94d9ae24d8c39bb72087db6b3c7ec`.

- Build run `34501152900`: success.
- normal Startup Benchmark run `34501152772`: success.
- exact-pack smoke run `34501152770` (#919): success, including aggregate.
- Fixture: `exact-pack-2026-09-02-v1`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.
- Warm measured process reached `main_menu` at 81,845 ms; `bootoptim_mixin_errors=0`.
- Resource selection exactly matched the fixture, one effective reload, zero resource-contract issues.
- Prime produced 3 Connector cache sidecars. The measured second process recorded 3 transform-cache decisions: **3 hits / 0 misses**.
- Connector trace: 32 scopes, 3 cache events. Analyzer integrity gates passed.
- Connector dependency-locator callback wall: **714.567 ms**. Causal overlap with structured `dependency_discovery`: **714.567 ms / 100%**.

Warm phase union wall (nested scopes are not added to parents):

| Phase | Union wall (ms) |
| --- | ---: |
| dependency-locator callback | 714.567 |
| locateFabricMods | 700.095 |
| split-package merge | 259.022 |
| cacheTransformableJar | 143.086 |
| Fabric metadata | 73.571 |
| dependency resolution | 71.705 |
| transform-cache validation | 57.057 |
| └ readAllBytes | 26.007 |
| └ SHA-256 | 30.297 |
| module descriptor | 8.666 |
| fresh IModFile construction | 10.789 |
| embedded JarJar callback | 9.390 |
| Fabric candidate scan | 2.245 |
| Forge package filter | 2.174 |
| transform dispatch | 0.289 |
| original-path callback | 0.011 |

Exclusive residuals from the measured nesting:

- transform-cache sidecar/output/other validation residual: **0.753 ms** total;
- `locateFabricMods` exclusive residual outside the named children: **212.960 ms**;
- dependency-locator callback publication/error-wrapper residual outside `locateFabricMods` + final callbacks: **2.898 ms**.

The three warm cache hits therefore spend **56.304 ms** in full-input `readAllBytes` + SHA-256 before Connector can accept its sidecar/output as current. This is real recurring warm work, but under Connector's current contract it is the content-validation mechanism, not automatically removable redundancy. The sidecar/output checks themselves are sub-millisecond in aggregate.

## Source interpretation

Pinned `JarTransformer.cacheTransformableJar` performs, in order, `FabricJarReader.readModMetadata`, `TransformerUtil.getCached`, and `getModuleName`. On a hit the later `transform()` dispatch is effectively free (0.289 ms here), confirming the existing transform output cache is functioning and must not be duplicated.

Pinned `TransformerUtil.getCached` computes the cache version plus a SHA-256 over `Files.readAllBytes(input)` before checking the `.input` sidecar and output file. Avoiding the measured 56.304 ms would therefore require an equally strong already-trusted content identity. This run does not reveal one. Replacing that identity with mtime/size, BootOptim's cheaper scan-cache identity, or an unverified persisted digest would weaken Connector's invalidation semantics and is not justified.

Fabric metadata (73.571 ms) is not dead warm work: `ConnectorLocator` immediately consumes it for ignore/duplicate decisions, nested-JAR discovery, dependency resolution, split-package metadata, and final Fabric-to-FML metadata construction. Persisting a second metadata cache would duplicate/expand Connector cache semantics and would need to version environment, dependency/version overrides, mixin metadata and input identity. No such safe existing identity/result is demonstrated here.

The 259.022 ms split-package merge is the largest named warm child, but source shows it constructs `SecureJar` views, enumerates module descriptor packages, compares Fabric jars against each other and already-loaded BOOT/SERVICE/FML packages, determines merge ownership/exclusions, and returns path/filter combinations consumed by final `IModFile` construction. This is live module-compatibility work whose inputs include the current loaded module set; it is not a stable transform-output cache hit and is not classified as redundant.

Two source-level redundancies are real and version-pinnable but are too small/unbounded by this profile to justify a production patch:

1. `discoveredJars` is first filtered by `!shouldIgnoreMod(...)`, then the subsequent nested-JAR stream calls the same `shouldIgnoreMod` again on every element of that already-filtered list with unchanged loaded-ID/module-name inputs. The second predicate is dead duplicate checking.
2. `cacheTransformableJar` eagerly computes `getModuleName(input)` for every Fabric jar, while the only use in this pinned `ConnectorLocator` path is guarded by `metadata.generated() && loadedModuleNames.contains(jar.moduleName())`. For non-generated Fabric mods that descriptor lookup is dead work. The profile bounds all module-descriptor work to only 8.666 ms total, and it does not expose a per-scope generated flag, so it would be incorrect to label all 8.666 ms removable.

The 212.960 ms exclusive `locateFabricMods` residual includes necessary orchestration not separately scoped here: prior-mod projections/module-name collection, duplicate handling, nested-JAR traversal if present, rename-library path collection, stream/list construction and safeguards. There is no evidence in this run that it is one redundant operation; treating it as savings would repeat the attribution error #239 was designed to avoid.

## Decision

**Diagnostic complete; no production optimization is justified by this warm profile.**

The existing Connector transform cache is healthy (3/3 hits). Its warm validation cost is dominated by content read+SHA (~56 ms total), but that work is correctness-bearing unless a trusted digest already exists. Metadata, dependency resolution and split-package work are live consumers of current input/module state. The only source-proven redundancies found are small predicate/module-name work and do not explain the material Connector wall.

Do not add a BootOptim transform cache, do not widen the generic discovery/scan cache, do not reuse live `ZipFS`/`JarContents`/`SecureJar` instances, and do not modify scheduling. If this subsystem is reopened, the next diagnostic should split the **259 ms split-package merge** and **213 ms locateFabricMods exclusive residual** by jar/package/loaded-module source to look for repeated package enumeration with identical live inputs. That is a diagnostic target, not a claimed saving.

# Connector split-package / locate residual attribution — 2026-09-10

Status: **PROFILED / NO-GO FOR PRODUCTION**

## Scope

Agent 112 continuation of diagnostic PR #243. Authority at start was `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`. The branch imports #243 only to reuse its already-gated Connector warm-profile harness. No transform-cache redesign, persistent metadata, live `ZipFS`/`JarContents`/`SecureJar` reuse, scheduler change, laptop run, or production fast path was implemented.

Exact owner remains Sinytra Connector `2.0.0-beta.17+1.21.1`, source commit `8b27f1ad042aae8037bcc522b321c03fcce1a12a`.

## Instrumentation

The copied exact-pack Connector JAR is patched offline, version/shape/signature gated and fail-closed before Minecraft starts. The diagnostic preserves all stock branches, values, exceptions, callback order, module/classloader state and threads.

Inside `SplitPackageMerger.mergeSplitPackages` it records non-overlapping child scopes for:

- each transformed Fabric output from `SecureJar.from(path)` through `descriptor().packages()`;
- each existing FML `IModFile` from `getSecureJar()` through `descriptor().packages()`;
- each BOOT/SERVICE `Module.getPackages()` call;
- each actual `analyzeJar` split-package operation, tagged by package.

Inside `ConnectorLocator` it additionally records previous-mod projection, `shouldIgnoreMod` per `TransformableJar`, duplicate handling, nested discovery and nested preparation. Process-local diagnostic identity IDs distinguish repeated references to the exact same loader object from distinct objects with equal-looking `toString()` output. They are observation only and are never used to alter startup.

`result.json` contains per-phase resource grouping (calls, unique resources, repeated calls, aggregate/max wall) plus the parent/DAG integrity and dependency-discovery overlap inherited from #243.

## Hosted exact-pack result

Fully gated runtime head: `bb6674d17f1dadd4241bc44b33cfd30f875cb2be`.

- Build run `34520954883`: success.
- normal Startup Benchmark `34520954858`: success.
- exact-pack smoke `34520954859`: success including aggregate.
- exact-pack artifact: `exact-pack-result-smoke-1`, id `10169831907`.
- fixture: `exact-pack-2026-09-02-v1`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.
- measured warm process reached `main_menu` at **87,469 ms**; `bootoptim_mixin_errors=0`.
- resource selection matched all 14 expected packs, one effective reload, zero selection issues.
- Connector dependency-locator callback: **789.315 ms wall**, with **789.315 ms / 100%** overlap inside structured `dependency_discovery`.
- `locateFabricMods`: **775.209 ms wall**; exclusive residual after all named direct children: **235.679 ms**.

Split-package decomposition (hosted warm wall, nested scopes are not added):

| scope | wall ms | calls / unique resources |
| --- | ---: | ---: |
| split-package merge parent | 244.691 | 1 |
| existing FML mod packages | 218.948 | 294 / 290 object identities |
| transformed Fabric jar packages | 13.132 | 3 / 3 |
| BOOT/SERVICE module packages | 0.062 | 90 / 90 |
| actual `analyzeJar` split packages | 0 | 0 |

Thus **89.48%** of split-package parent wall is the serial existing-FML package pass. No Fabric-vs-Fabric split package required `analyzeJar` in this exact pack. BOOT/SERVICE enumeration is negligible and has no repeated module identity.

The exact-identity rerun is the important correction to the prior text-only profile. Text grouping had made nested multi-mod files look heavily repeated, but the identity trace shows **294 calls over 290 distinct `IModFile` objects: only four exact-object repetitions**. Each repeated object appears twice. Their duplicate cached-call tails are only about **0.008 ms total** when bounded as `total - max` per two-call identity. The repeated exact objects were Forgematica, MaFgLib, BoccHUD and the top-level Forgified Fabric API file. Therefore the source-visible multi-mod projection does not produce material identical-input repetition here; most equal-looking nested rows are distinct loader objects.

`shouldIgnoreMod` is the other confirmed identical-input duplication: 6 calls / 3 `TransformableJar` identities, exactly twice each, but the whole scope is only **0.059 ms**. Duplicate handling is **1.242 ms** and nested discovery **2.658 ms**. No nested preparation executed in this warm run. Previous-mod projection is **65.107 ms** but executes once over the live discovered-mod list; the trace does not show a repeated identical input/result boundary inside it.

## Why the 218.948 ms is not removable cache work

SecureJarHandler intentionally computes module descriptors lazily because computing the package list is expensive; `LazyJarMetadata.descriptor()` caches the descriptor per live jar object. Later module-layer construction necessarily consumes each `SecureJar` descriptor through `JarModuleFinder`, where descriptor computation is deliberately parallelized across independent jars. Connector's split-package check currently forces many of those descriptors earlier and serially, but simply removing that force would move/failure-shift descriptor computation rather than prove CPU elimination.

A targeted package-membership implementation is also not currently safe: Connector needs the exact package semantics of the live `SecureJar`/module descriptor, including filters and modular jars. Reimplementing package detection from paths or BootOptim scan data would create a second semantic definition and alter failure timing. BootOptim's persistent scan cache cannot serve as an equivalent source here: its entry is read only later from `CachedModFile.compileContent()`, after dependency discovery, and its current persistent identity is not Connector's strong content identity. Pulling that cache forward would widen its lifecycle and invalidation contract.

## Safe mechanism evaluated

A method-local identity de-duplication inside only the `existing` loop of `SplitPackageMerger` would be semantically narrow: preserve first-encounter order, skip only the exact same `IModFile` object, and leave `loadedModFiles` unchanged for `TransformerEnvironment`, dependency resolution, rename classpath and callbacks. Identity, not `equals`, is required.

Source-wise this is safe for the package-union operation because re-adding the same object's same descriptor package set to `existingPackages` is idempotent. However, the exact-pack identity trace reduces its measured direct ceiling to about **0.008 ms**, so a Connector bytecode/version patch has no startup justification. It is not promoted to an experiment.

## Decision / reopening criterion

**No production candidate is justified for the current exact pack.** The apparent large repetition premise is discarded: the expensive 218.948 ms pass is overwhelmingly first-time work over distinct live FML jars, while exact-object duplicate work and duplicate `shouldIgnoreMod` checks are sub-millisecond.

Do not implement identity dedupe, a package metadata cache, a scan-cache bridge, live-object reuse, targeted filesystem package emulation, or parallel split-package scheduling from this evidence.

Reopen only if one of these material premises changes:

1. a future Connector/SecureJarHandler version exposes an authoritative package-membership/package-set result that can be reused without changing descriptor/failure semantics; or
2. a new exact-pack profile with object identity proves exact repeated `existing` inputs consume at least **100 ms causal wall or 10% of the Connector callback**, at which point test only method-local first-occurrence identity dedupe in a separate A/B; or
3. scheduling constraints are explicitly reopened and a separate experiment can prove that deferring first-time descriptor materialization back to SecureJarHandler's stock parallel module-finder stage preserves failure order and moves TTMM, not merely the split-package counter.

PR: #245. Parent diagnostic: #243.

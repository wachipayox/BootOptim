# FML discovery artifact critical-path diagnostic — Agent 106 — 2026-09-10

Status: **DIAGNOSTIC / PROFILE-ONLY / NO OPTIMIZATION**

## Scope and authority

Assignment authority is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`, Minecraft 1.21.1, NeoForge 21.1.248 and the exact-pack FML 4.0.43 line. This branch is intentionally stacked on diagnostic PR #202 (`c0f995a7afa5480f3eff91f18decb5fd9f5bb619`) so it can use the #200/#201 structured trace contract already validated in SERVICE. It must be rebased after that diagnostic stack is resolved. Nothing here is a production optimization.

The physical observation motivating this work is root discovery around 5–8 s and dependency discovery around 14–22 s. Hosted absolute time is not a laptop baseline.

## Historical closures honored

- The persistent class/annotation scan cache already exists in `CachingModFileReader`; this work does not add, widen, or reinterpret it.
- PR #134 closed a generic dependency/JarJar result cache: third-party dependency locators can depend on runtime/config state, and fresh `jij:` JarContents / normal reader processing have lifecycle semantics that are not represented by a generic input fingerprint.
- PR #185 measured BootOptim's wrapper-fallback scan at only ~36.7 ms hosted and closed it as a production target. This work does not reopen that path.
- PR #202 demonstrated that directly transforming bootstrap-loaded FML `ModLoader` is unreliable in this launch topology. This diagnostic therefore does not patch `ModDiscoverer`, dependency locators, futures, callbacks, or discovery ordering.

## Exact source map

FancyModLoader `ModDiscoverer` performs root locator callbacks sequentially, constructs a `DiscoveryPipeline`, calls `JarContents.ofPaths`, and then iterates `IModFileReader`s for each artifact. After the first unique-list pass it invokes each `IDependencyLocator.scanMods` and feeds resulting artifacts through the same reader pipeline. `ModFileDiscoveryAttributes` carries the root locator, dependency locator and parent mod-file identity.

BootOptim's production `CachingModFileReader` is already the highest-priority standard reader. Its accepted path probes `META-INF/neoforge.mods.toml`/manifest type and constructs a `ModFile`. The pinned FML `ModFile` constructor performs `ModFileParser.readModList`, Jar/module metadata creation, mixin metadata and access-transformer metadata setup. Class/annotation `compileContent()` remains a separate later operation and retains the existing persistent scan-cache behavior.

## Instrumentation

The SERVICE registration is replaced on this diagnostic branch by `DiscoveryArtifactProfilingModFileReader`, a decorator around the exact production `CachingModFileReader`. With artifact detail disabled it is one delegate call and preserves the same reader decisions and `reader` discovery attribute because the delegate still owns `withReader(this)`. With `bootTrace=profile|development` plus `-Dboot_optim.profileDiscoveryArtifacts=true` it emits one lexical `fml_discovery_artifact_metadata` task per reader invocation.

Each task records:

- artifact path and filesystem provider scheme (`file`, `jij`, etc.);
- physical size when meaningful;
- root locator class, dependency locator class and parent artifact;
- accepted mod id and number of contained mods;
- pinned FML/NeoForge identity.

Sibling artifact tasks on a given discovery thread are causally chained in actual invocation order. Root and dependency discovery are also chained (`dependency_discovery` depends on completed `root_mod_discovery`). Parent task ids are used only when the reader executes lexically on the phase-owner thread. The structured analyzer therefore unions overlap and never converts a sum of artifact walls into presumed savings.

### ZipFS / I/O / callback attribution

Direct instrumentation of JDK ZipFS or bootstrap FML is deliberately avoided. The same opt-in starts one bounded JFR recording at literal root-discovery begin and stops it at dependency-discovery end. Enabled events are FileRead/FileWrite (>=1 ms), ExecutionSample (10 ms), ThreadPark (>=2 ms), and JavaMonitorEnter (>=2 ms), all with stacks.

No JFR event is consumed while startup is timed. At normal JVM shutdown the stopped recording is dumped and summarized to the already-collected console. The summary ranks physical artifacts by I/O duration/bytes and classifies sampled stacks as metadata parse, dependency selection, ZipFS/ZipFile, SecureJar/JarContents, Connector callback, locator callback, class scan, or other. Blocking totals are reported separately. This is attribution evidence, not a benchmark.

The existing `BOOTOPTIM_SCAN_CACHE ... elapsed_ms=` line is an actual per-file measured span and may be used to identify where class scanning remains material; its hit/miss/finished counters are **not** converted into TTMM savings.

## Hosted interpretation and scaling gates

Use one exact-pack **profile smoke**, never an optimization A/B. Required JVM arguments:

```text
-Dboot_optim.bootTrace.mode=profile
-Dboot_optim.bootTrace.origin=hosted_exact_pack
-Dboot_optim.bootTrace.endpoint=main_menu
-Dboot_optim.profileDiscoveryArtifacts=true
```

Validity gates:

1. Build and normal Startup Benchmark remain green with default/off behavior.
2. Exact-pack uses fixture `exact-pack-2026-09-02-v1`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639` and reaches `main_menu` with the normal resource contract.
3. Structured trace has one header/summary, zero loss/failures, balanced tasks, no missing dependencies/cycles and valid lexical nesting.
4. Root -> dependency is a real dependency edge; per-artifact sibling edges represent observed serial callback order only.
5. JFR has explicit root-start/dependency-stop markers and a shutdown summary. Its sampling counts are not task wall.

Cross-sectional scaling in the hosted exact pack is evaluated against real measured artifact metadata wall and I/O by: physical artifact size, filesystem scheme, root vs dependency locator, nested-parent status, contained-mod count, and class-scan span. A candidate is material only when wall/critical-chain contribution and a concrete data dimension move together; raw artifact counts are insufficient.

## Candidate decision matrix

| Candidate | Evidence required | Safety boundary | Current decision |
| --- | --- | --- | --- |
| One named mod/artifact with repeated metadata parse | same content/provenance appears repeatedly on the winning chain and parse wall is material | version + content digest + parser/schema identity; reconstruct fresh runtime-bound `ModFile` state | investigate only; no cache implemented |
| JarJar dependency-selection metadata | dependency-selection samples/wall dominate and repeated source-set evaluation is demonstrated | exact dependency-source digests + FML/JarJar version + config/runtime inputs; cache selection data only, never live JarContents/FS | historical #134 frontier only; no implementation |
| Repeated physical JAR/ZipFS opens | same artifact shows material repeated FileRead/ZipFS wall in distinct causal steps | first prove ownership/close lifetime and that reuse does not retain stale `jij:` filesystem state | investigate only |
| Specific third-party `IDependencyLocator` / Connector callback | callback class dominates samples and its emitted artifacts identify the owner mod | fork/owner-specific contract; preserve callback order/failures and runtime/config sensitivity | preferred if hosted identifies a culprit |
| Generic scan-cache widening | any count-only or aggregate hit/miss argument | none | **NO-GO**; explicitly out of scope |
| BootOptim wrapper mods-directory fallback | #185 threshold would need >=500 ms or >=10% physical root wall | packaged-origin verification | **NO-GO here**; not reopened |

## Result section

The hosted evidence section is intentionally filled only from a completed profile smoke. Until those gates pass there is no candidate and no savings claim.

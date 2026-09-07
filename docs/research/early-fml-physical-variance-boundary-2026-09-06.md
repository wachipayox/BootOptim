# Early NeoForge / ModLauncher / FML physical-variance boundary — 2026-09-06

Status: **RESEARCH COMPLETE / NO RUNTIME CANDIDATE YET / ONE BOOTOPTIM-OWNED I/O FRONTIER IDENTIFIED**

Agent: 35

Authority reviewed: `agent/integration-current` @ `b0aa2472d58e3afc56a380e026c99ffe87000f22`.

This audit asks why the early loader is materially longer on the fixed 2C/4T laptop than on hosted CI, without turning a coarse wall-time gap into an unsupported cache, concurrency or OS-tuning claim.

The conclusion is deliberately split:

1. there is **no justified generic FML/ModLauncher optimization** beyond already-owned research;
2. there is one narrower BootOptim-owned boundary worth measuring before reopening generic I/O work: the fallback in `DiscoveryStartLocator.locateWrapper()` can enumerate `mods/` and open every JAR while looking for BootOptim's outer wrapper;
3. current retained evidence cannot tell whether that fallback is active or material on the laptop, so no executable change is proposed here;
4. one future physical run can close the ambiguity if, and only if, a hosted-gated low-cardinality diagnostic first reports that exact self-time together with JVM CPU/class/JIT and JFR I/O/wait evidence.

## Required history and non-overlap

Reviewed before selecting a direction:

- `AGENTS.md`, root `README.md`, `docs/research/README.md`, `docs/research/mixin-pipeline.md`;
- PR #18 / #19 vanilla bootstrap and rejected parallel class prewarm;
- PR #43 / #46 / #48 Mixin side-load, ClassInfo and external writer-tail results;
- PR #65 / #68 slow-hardware startup/JFR campaign;
- issue #67 persistent post-Mixin transformed-class cache research;
- PR #99 FML lifecycle diagnostic;
- PR #126 / #133 ModernFix reload-executor lease history;
- PR #134 dependency-discovery/JarJar persistence boundary;
- PR #135 vanilla-bootstrap variance diagnostic;
- PR #136 resource-open audit;
- PR #137 ModernFix effective-option audit;
- PR #141 FilePackResources/reload correlation.

This document does **not** reopen any of those mechanisms.

### Why this is not PR #134

PR #134 asks whether dependency-locator/JarJar results can be persisted. It rejects a generic cache because `IDependencyLocator` is an extensible ServiceLoader SPI with undeclared semantic inputs, while even a narrow JarJar-selection cache must still rebuild fresh filesystems, `JarContents`, readers and later FML state.

The boundary identified here occurs **inside BootOptim's own highest-priority root locator before the normal root locators**. It is not dependency selection, JarJar result reuse, `ModFile.compileContent()` reuse, or suppression of another locator. If the fallback is expensive, removing its redundant wrapper search would remove only BootOptim-owned work.

### Why this is not PR #135

PR #135 correctly redefines the log line commonly described as “ModernFix bootstrap”: ModernFix 5.27.14's measurement mixin times vanilla `net.minecraft.server.Bootstrap.bootStrap()`. Its diagnostic therefore starts at that vanilla method boundary.

This audit focuses on the earlier SERVICE/FML discovery path and on BootOptim's own root-locator lookup. It does not duplicate PR #135's Bootstrap snapshots and does not attribute its measured wall to ModernFix self-time.

### Why this is not the ModernFix lease

PR #126/#133 concern reload-executor scheduling after startup has progressed into the resource-reload subsystem. Nothing here changes ModernFix executor ownership, thread counts, resource scheduling or feature settings.

## Existing numbers and metric types

### Hosted discovery ceiling

The cold hosted evidence retained by #99/#134 is:

- root discovery: about **476.726 ms wall**;
- dependency discovery: about **6,445.528 ms inclusive wall**;
- BootOptim scan cache: **240 misses / 0 hits**;
- `ModFile.compileContent()` window: about **3,591.595 ms wall**.

The dependency number is a cold inclusive phase, not a warm savings claim. The arithmetic residual after subtracting `compileContent()` is not exclusive and must not be called recoverable TTMM.

### Hosted vanilla Bootstrap discriminator

PR #135's dedicated exact-pack smoke, run `33998260443`, reached the menu at **93,683 ms wall**, with zero BootOptim Mixin failures and the normal blocks atlas `8192x8192x2`.

At the exact vanilla Bootstrap boundary it reported:

- wall: **4,395.517 ms**;
- caller CPU: **4,218.209 ms**;
- process CPU: **14,240 ms inclusive**;
- loaded-class delta: **8,096**;
- JVM compiler-time delta: **8,904 ms aggregate**;
- GC time: **91 ms**.

Caller CPU was therefore about 96% of that hosted wall. This is strong evidence that a normal hosted Bootstrap is CPU/class/JIT heavy. It is not evidence that every physical outlier has the same cause.

### Historical class-transform evidence

PR #18 measured on an earlier fast reference:

- `Bootstrap.bootStrap()`: **4,239.26 ms wall**;
- `FireBlock.bootStrap()`: **3,137.71 ms wall**;
- `Blocks.<clinit>`: **2,884.64 ms wall**.

The preceding JFR sample set had 23/46 FireBlock samples containing `ClassTransformer.transform`. PR #19 then tested a 200-class non-initializing parallel prewarm and was rejected end-to-end. A slow laptop does not reopen that exact mechanism.

### Slow-hardware whole-startup evidence

The #65/#68 campaign used the same diagnostic build on the fast reference and the 4-processor / 6 GiB laptop:

- fast mod entrypoint: **28,541 ms wall**;
- laptop mod entrypoint: **112,594 ms wall**;
- pre-entrypoint scaling: about **3.95x**;
- fast main menu: **68,920 ms**;
- laptop main menu: **337,244 ms**.

The laptop JFR contained **8,899 `jdk.FileRead` events**, physical reads from mod JARs, and repeated `FileChannelImpl.implRead` contention groups with averages around 11–23 ms and maxima up to 366 ms. It also showed ASM/Mixin/classloader/JIT pressure.

However that recording had `jdk.ClassLoad=0` and `jdk.ClassDefine=0`. It therefore cannot separate early classloading volume from I/O or waits at the exact root/FML boundaries. Whole-run JFR hotspots must not be projected onto a narrower early phase.

### Current physical observation

The current project handoff reports roughly:

- root/FML visible wall: **18–21 s**;
- later vanilla-bootstrap-visible region: roughly **28–34 s**;
- one inherited **~15.84 min** physical outlier contaminated by old JVM/harness state.

The 15.84-minute value is excluded from any normal baseline, average or causal conclusion.

## Exact source boundaries

### ModLauncher / FML

NeoForge/FML 1.21.1 uses ModLauncher **11.0.3** on this source line.

`FMLServiceProvider` exposes the normal ModLauncher lifecycle:

- `initialize(IEnvironment)`;
- `beginScanning(IEnvironment)` -> `FMLLoader.beginModScan(...)`;
- `completeScan(IModuleLayerManager)` -> `FMLLoader.completeScan(...)`;
- `onLoad(...)`;
- `transformers()`.

`FMLLoader.beginModScan()` constructs `ModDiscoverer` and calls `discoverMods()`. `completeScan()` creates `LanguageProviderLoader`, calls stage-2 validation and starts the background scan handler.

ModLauncher's transformation-service handler loads services into the SERVICE layer, calls their lifecycle methods, builds the transforming GAME classloader, gathers transformers and triggers scan completion. The public transformation-service API does **not** expose a supported per-class post-Mixin cache/replay hook.

That matches issue #67's blocker: persisting post-Mixin class results would require a complete transformation fingerprint plus a safe plugin-decorator interception point that ModLauncher 11 does not currently expose to BootOptim.

### What BootOptim already observes

At current integration, `EarlyStartupProbeService` is an `ITransformationService` in the SERVICE layer. Its current markers record uptime/heap at:

- `transformation_service_construct`;
- `transformation_service_initialize`;
- `transformation_service_on_load`.

`DiscoveryProfiler` separately records root and dependency start/end wall time.

Those markers are useful clocks, but they do not carry caller/process CPU, class-count, JIT or GC deltas. A long physical root/dependency wall therefore remains ambiguous between active Java work and blocked/serialized time.

## Newly identified BootOptim-owned I/O frontier

`DiscoveryStartLocator` has priority `Integer.MAX_VALUE`, so it runs before normal root-mod locators. It starts the root timer and then locates the BootOptim wrapper so the nested ordinary BootOptim mod can be exposed as a root candidate.

The fast path is conservative:

1. inspect the locator class `CodeSource`;
2. only accept it when the URI scheme is `file`;
3. verify the candidate JAR contains BootOptim's wrapper marker.

The fallback is materially different:

1. list `gameDir/mods`;
2. filter every regular `.jar`;
3. call `isBootOptimWrapper(path)` for each candidate;
4. `isBootOptimWrapper` opens the JAR with `ZipFile` and checks for one class entry;
5. only after filtering are matching paths sorted and the first selected.

Therefore, when the SERVICE-layer `CodeSource` is represented through UnionFS/Jar-in-Jar/pathfs rather than a plain `file:` URI, BootOptim may open **every top-level mod JAR** solely to rediscover its own wrapper. This happens serially at the beginning of the measured root-discovery window.

NeoForge's own `ServiceLoaderUtil.identifySourcePath(...)` demonstrates that FML knows how to unwrap service origins through `PathFileSystem` and `UnionFileSystem` to their backing paths. That utility is `@ApiStatus.Internal` and returns a human-readable string, not a supported physical-Path API suitable for blindly copying into production. It is source evidence that a more direct origin exists; it is not by itself a stable BootOptim contract.

### Why no optimization is implemented now

There is no retained marker proving that the fallback is used on the fixed laptop, no exact `wrapper_lookup_wall_ms`, and no count of JARs probed. The safe-path source observation is therefore a **candidate boundary**, not a measured bottleneck.

A production change that rewrites source-path unwrapping before measuring this would risk:

- coupling BootOptim to internal UnionFS/pathfs representation details;
- selecting the wrong physical backing path for nested/service-layer locations;
- breaking renamed wrappers or development layouts;
- turning an unmeasured rare fallback into maintenance burden.

The correct next step is one low-cardinality diagnostic, not an immediate locator rewrite.

## Rejected mechanisms in this audit

### Generic transformed-class persistence

Still blocked by issue #67. Correct invalidation would need the pre-Mixin transformed node plus NeoForge/FML/ModLauncher/Mixin versions, access transformers, all mixin configs/refmaps/plugins, Connector/compat inputs, relevant system/config state and schema identity. `IMixinConfigPlugin` may consult arbitrary runtime state. No supported ModLauncher 11 decorator/replay hook has been established.

### Generic class prewarm

PR #19 already tested the materially similar premise and regressed end-to-end. Do not reinterpret the laptop's class/JIT pressure as permission to rerun it.

### Generic JAR read-ahead

Sequential or asynchronous pre-reading can preserve transformation order, but it is not automatically harmless. On 2C/4T hardware it can contend with ModLauncher, Mixin, compiler threads and antivirus/storage; on fast hardware it can simply move page-cache work earlier without reducing the critical path.

No exact future consumer set, exclusive I/O tail, or idle overlap window is currently established. Whole-JAR read-ahead is therefore **NO-GO** on current evidence.

### Another discovery/JarJar cache

Closed by #134. This audit does not change that decision.

## Minimal future diagnostic contract

If implemented, it should be opt-in and diagnostic-only. It must not enumerate classes/resources merely for profiling and must not start new workers.

### Monotonic snapshots

At existing boundaries, record one snapshot containing:

- `System.nanoTime()` / JVM uptime;
- current-thread CPU if thread CPU timing is already available; never enable it merely for the probe;
- process CPU delta where available;
- loaded/total-loaded class counts;
- aggregate JVM compilation time;
- GC count/time deltas;
- heap usage;
- thread name/state.

Required phase rows:

- transformation-service construct -> initialize;
- initialize -> onLoad;
- root discovery start/end;
- dependency discovery start/end.

These are **wall + CPU/JVM-work discriminators**, not recoverable-savings estimates.

### BootOptim locator self-attribution

Within `DiscoveryStartLocator` only, add aggregate fields:

- `code_source_scheme`;
- `code_source_fast_path=true|false`;
- `fallback_used=true|false`;
- `candidate_jars_seen`;
- `wrapper_jars_opened`;
- `wrapper_lookup_wall_ms`;
- nested-mod extraction `cache_hit|copy` and copied byte count;
- failure/fail-open reason.

No per-JAR names or per-open clocks are needed.

`wrapper_lookup_wall_ms` is a real serial wall ceiling inside BootOptim's own root-locator turn. If it is 2 s, at most 2 s of that run's root wall can be attributed to this lookup; it still does not prove a 2 s TTMM improvement until a candidate A/B removes it.

### One bounded JFR slice

The single physical arbitration should additionally enable the events missing from #68 and retain only an early slice through vanilla Bootstrap / mod entrypoint:

- `jdk.ClassLoad` and `jdk.ClassDefine`;
- `jdk.FileRead` with a non-zero threshold such as 5 ms;
- `jdk.ThreadPark` and `jdk.JavaMonitorEnter` with a similar threshold;
- normal execution samples at a modest period (for example 20 ms);
- periodic thread CPU load.

The purpose is attribution, not a benchmark. FileRead wall must not be equated with raw disk time: filesystem, decompression, filter drivers/antivirus and page-cache misses can all contribute around the observed operation.

## Single physical-run protocol

Do **not** request a repeated cold/warm campaign, cache purge, reboot series or JVM-flag tuning.

A single run is justified only after an executable diagnostic implementing the contract above has passed Build + hosted exact-pack smoke with:

- exact fixture/resource selection;
- menu reached;
- zero BootOptim Mixin failures;
- all expected early markers present;
- profiler disabled by default.

Then perform one normal fixed-pack laptop launch using the existing Java/runtime configuration. Do not change `ActiveProcessorCount`, Java version, Windows settings, Defender/antivirus settings, process priority or pack contents for the measurement.

### Concrete hypothesis tested

> The excess 18–21 s early physical root/FML interval is either (1) measurable BootOptim-owned wrapper-search/JAR I/O, (2) active classloading/transformation/JIT CPU, or (3) blocked/serialized storage/wait time; one timestamp-aligned snapshot + JFR slice can distinguish those classes without changing loader order.

### Required evidence from that one run

- same-run BootOptim early markers and locator self-attribution;
- early JFR slice;
- `latest.log` / BootOptim startup log from that run only;
- exact BootOptim wrapper SHA/commit and exact pack identity;
- JVM start epoch/uptime, PID if available, Java runtime string and JVM argument fingerprint;
- main-menu marker and zero BootOptim Mixin failures.

### Decision table

**A. BootOptim fallback is material**

Reopen a source-origin optimization only if:

- `fallback_used=true`;
- `wrapper_lookup_wall_ms` is material (practical reopening threshold: >=500 ms or >=10% of measured root-discovery wall);
- JAR probes are non-trivial;
- the time is not an artifact of the diagnostic itself.

Candidate then: replace the all-JAR fallback with a version-gated direct source-origin resolution that is independently verified against the actual outer wrapper and fails back to the current scan. The performance gate is root-discovery wall and TTMM A/B, not “JARs opened”.

**B. Root/FML is CPU/class-transform dominated**

Evidence: high caller/process CPU ratio, large class-count/JIT deltas, execution samples in ModLauncher/Mixin/ASM/classloader paths, little long FileRead/park contribution.

Action: do not add read-ahead. Reopen only a specific transformer/classloading mechanism with a supported lifecycle boundary. Persistent post-Mixin reuse remains blocked unless issue #67 obtains a safe decorator/interception API and a complete semantic fingerprint.

**C. Root/FML is storage/filter/page-cache dominated**

Evidence: low/modest Java CPU progress while timestamp-aligned long `jdk.FileRead` events cover the gap, with JAR paths concentrated in the early interval.

Action: attribute the exact repeated physical operation first. A future read-ahead/index candidate must name the bytes/metadata it avoids or overlaps, preserve discovery/transform order, and show a real idle overlap window. Do not infer “antivirus” specifically unless native evidence proves it.

**D. Root/FML is serialization/wait dominated**

Evidence: low process CPU, no matching FileRead coverage, and parks/monitor waits spanning the wall.

Action: identify the owner/wait condition. Do not increase global concurrency. Any scheduling change must preserve ModLauncher/FML ordering and have an explicit owner/re-entry contract.

**E. The delay is before the JVM/BootOptim boundary**

If JVM uptime at `transformation_service_construct` is normal while an external launcher reports a much larger pre-Java interval, keep that time outside FML/BootOptim attribution. A Java-side optimization cannot claim it.

## Fast hardware vs 2C/4T safety

The diagnostic contract is safe in principle for both environments because it adds bounded snapshots and no workers, class preloads, transform changes or resource enumeration.

A future direct wrapper-origin fix, if proven, is also attractive on both: it removes redundant BootOptim I/O instead of adding overlap.

Read-ahead is different. It is especially risky on 2C/4T because ModLauncher/Mixin, JIT/compiler threads, FML/background work and filesystem filters already contend for scarce CPU/storage. It must not be enabled merely because it looks harmless on hosted hardware. A candidate would need bounded concurrency (normally one I/O task), an exact target set, cancellation/fail-open, and fast-host non-regression evidence.

## ModernFix interaction

ModernFix 5.27.14 is not the owner of the early root locator. This audit does not change any ModernFix option.

For the later Bootstrap boundary, keep PR #135's interpretation: ModernFix's measurement mixin observes vanilla `Bootstrap.bootStrap()`. ModernFix transformations may still appear in class-transform stacks, but that does not make the stopwatch exclusive ModernFix self-time.

The reload-executor lease #126/#133 is unrelated and remains closed as a production direction.

## Invalidating inherited Prism/JVM evidence

The inherited ~15.84-minute result is treated as contaminated and must never be mixed with a fresh run.

For any future physical arbitration:

1. rotate/archive the previous launcher/game logs before launch; do not parse an inherited `latest.log` as same-run evidence;
2. bind every retained marker to the same JVM start epoch/uptime and, where available, PID;
3. record exact BootOptim commit/wrapper identity, exact pack fixture/resource selection, Java runtime and JVM-argument fingerprint;
4. reject any marker timestamp that predates the current JVM start or belongs to a different wrapper/pack identity;
5. report Prism/harness time separately from JVM uptime; never subtract one stale clock domain from another;
6. do not include the contaminated 15.84-minute run in medians, ceilings or regression percentages.

This is evidence hygiene, not a recommendation to change Prism, Java or Windows.

## Final decision

**No executable PR is justified from the retained evidence.** Consequently this branch is documentation-only and does not require a hosted runtime smoke.

The only newly actionable frontier is BootOptim's own wrapper lookup fallback. It has a clear serial critical-path location and a safe fail-open shape, but its physical activation/cost is currently unknown.

### Reopen in this order

1. Add the low-cardinality early snapshots + `DiscoveryStartLocator` self-attribution on a diagnostic-only branch.
2. Pass Build + hosted exact-pack smoke before any physical use.
3. If the hosted diagnostic is semantically clean, authorize **one** fixed-laptop run using the protocol above.
4. Implement a wrapper-origin optimization only if that run proves the fallback material.
5. Otherwise route the result to CPU-transform, I/O, wait/serialization, or outside-JVM ownership using the decision table and close the other lanes.

## Links

- BootOptim: https://github.com/wachipayox/BootOptim
- PR #18: https://github.com/wachipayox/BootOptim/pull/18
- PR #19: https://github.com/wachipayox/BootOptim/pull/19
- issue #67: https://github.com/wachipayox/BootOptim/issues/67
- PR #68: https://github.com/wachipayox/BootOptim/pull/68
- PR #99: https://github.com/wachipayox/BootOptim/pull/99
- PR #134: https://github.com/wachipayox/BootOptim/pull/134
- PR #135: https://github.com/wachipayox/BootOptim/pull/135
- PR #136: https://github.com/wachipayox/BootOptim/pull/136
- PR #137: https://github.com/wachipayox/BootOptim/pull/137
- PR #141: https://github.com/wachipayox/BootOptim/pull/141
- NeoForge FML `FMLServiceProvider` 1.21.1: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/loading/FMLServiceProvider.java
- NeoForge FML `FMLLoader` 1.21.1: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/loading/FMLLoader.java
- NeoForge `ServiceLoaderUtil` 1.21.1: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/util/ServiceLoaderUtil.java
- ModLauncher: https://github.com/MinecraftForge/ModLauncher

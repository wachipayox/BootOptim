# Laptop extreme startup variance causal ranking — 2026-09-06

Status: **PROFILED / P0.2 CAUSAL TRIAGE COMPLETE / NO NEW RUNTIME PROBE**

Base audited: `agent/integration-current` @ `b0aa2472d58e3afc56a380e026c99ffe87000f22`.

Scope: explain the large run-to-run spread of the fixed exact-pack Windows laptop before proposing further startup optimizations. This is not an optimization branch and makes no runtime change.

## Metric discipline

- `TTMM`, phase partitions and reload/title tails below are **elapsed wall on the observed critical sequence** unless stated otherwise.
- FilePackResources totals are **inclusive wall aggregates** and may overlap worker execution; they are not TTMM savings.
- Process CPU is cumulative CPU consumed by the Minecraft JVM across all its threads. It is not decoder CPU, render CPU, disk time or GPU time.
- Current-thread CPU is CPU charged only to the measured Java thread.
- `wall - process CPU` is an unresolved bucket containing blocking, filesystem/page-cache latency, native waits, OS descheduling, external-process contention and GPU/present waits. It must not be renamed to any one of those causes.

## Evidence already strong enough to classify

### Confirmed: benchmark/JVM-age contamination can create extreme false startup time

PR #130 documents one contaminated run where `mod_entrypoint=985.056 s` although the visible FML/ModernFix sequence remained near its normal duration. Subtracting first-visible-log -> entrypoint leaves an unobserved prefix of about **842.733 s wall**. The Java process had been left alive during Prism preparation. This is a harness/process-boundary failure, not a NeoForge phase.

Decision: any physical result whose JVM start/uptime is inconsistent with the runner's intended launch boundary is invalid before aggregation. Do not use it to rank startup work.

### Confirmed: storage/page-cache state materially changes some resource work

PR #140 measured FilePackResources ZIP enumeration at **5,275.585 ms inclusive wall** on one physical run. PR #141 repeated the same pack under the combined reload-boundary diagnostic and measured **1,321.009 ms inclusive wall**, with the same dominant external ZIP. This ~4x change proves that storage/page-cache state materially changes this subphase on the target laptop.

The second run also places that work inside a much larger ModelManager preparation future. Therefore the 5.276 s vs 1.321 s difference is evidence of an I/O/page-cache-sensitive surface, not a summable TTMM cause and not an established explanation of the full 60+ s run spread.

### Confirmed local mechanism, not primary P0.2 cause: FancyMenu stock busy-spin

`variance-fixed-021` measured FancyMenu waits at **18.298 s inclusive wall / 18.234 s owner-thread CPU**, process CPU **36.047 s**, GC **0 ms**, heap **3341 -> 3629 MiB**, and available physical memory **1465 -> 795 MiB** across the preload. This proves active CPU contention inside that fixed interval.

It does not explain the full run-to-run shape: same-setting runs vary strongly before and after the wait, and the later cooperative-wait experiment regressed hosted critical-path wall. This lane remains separate from the P0.2 root-cause ranking.

### Rejected as primary explanation: repeated GLSL fallback / Voxy save

The physical shader/Voxy diagnostic measured six capability probes at **155.418 ms inclusive wall / 62.500 ms current-thread CPU** and one Voxy save at **5.534 ms wall**. Counts and error texts are stable across the comparable laptop runs. Those operations are orders of magnitude below the observed reload/title variance and do not explain it.

The software graphics backend can still influence later native upload/presentation or compete for CPU. The established shader-error symptoms themselves are not the cause.

## Ranked remaining causes

### 1. Probable: changing system contention / scheduler availability, including interaction with the software renderer

Confidence: **probable, not confirmed**.

Why it ranks first:

- Same-setting physical runs do not scale by a uniform factor; different serial partitions move in opposite directions.
- PR #126's physical reverse-order lease experiment produced enormous pre-entrypoint drift (for example `104.476 s` vs `365.481 s` mod-entrypoint wall) while the direct reload interval changed far less. A fixed software toggle cannot explain that shape.
- The target is a 2C/4T laptop with software-render/native work competing with Java workers. `variance-fixed-021` accumulated roughly two CPU-seconds per wall-second during FancyMenu preload, proving that core contention can be real on this machine.

What would confirm it:

A single valid fixed-setting run must show a materially slow wall interval together with high Minecraft process CPU/core-equivalent consumption or a host trace showing runnable/descheduled Minecraft threads while another process owns CPU. High process CPU supports CPU/work contention; low process CPU leaves external scheduling/native/I/O unresolved.

What would reject it as the dominant cause:

A slow run where the enlarged interval has low Minecraft process CPU, no host CPU saturation and a clear hard-fault/disk or present/native wait signature.

### 2. Probable: memory pressure causing cache eviction and possibly paging

Confidence: **probable pressure, paging not yet demonstrated**.

Evidence:

- Run 021 dropped available physical memory from **1465 MiB to 795 MiB** during preload while heap rose **3341 -> 3629 MiB**.
- The pack exercises a very large resource set and 8192x8192x2 atlas on a low-memory Windows system.
- The FilePackResources 5.276 s -> 1.321 s swing is compatible with changing file/page-cache residency.

Limits:

Available memory is not a hard-fault counter. GC=0 during the measured preload disproves GC as the explanation of that particular 18.3 s interval, but says nothing about paging elsewhere. No current evidence shows how many hard faults or swap reads occurred during the long reload phases.

Separating measurement:

For one physical diagnostic, record host memory before launch and a bounded OS trace/counter set covering hard faults/page reads, committed bytes and paging-file activity for the Minecraft PID and system. Correlate only with monotonic JVM phase markers after the run; do not poll `latest.log` during execution.

### 3. Confirmed subcause / probable contributor: filesystem + page-cache state

Confidence: **confirmed for ZIP enumeration; probable broader contributor; not confirmed as TTMM-dominant**.

The 5.276 s vs 1.321 s inclusive FilePackResources result is the direct proof. Historical PR #69 also found large task-sums in resource opens/sprite loads, but those overlap and had observer effect. PR #138/#141 places large physical time in atlas/model preparation before upload.

Separating measurement:

Use the same one-run host trace to count Minecraft file reads, read latency and hard faults by time window. A storage/page-cache conclusion requires a slow monotonic JVM interval to coincide with file/page-fault activity; the existing inclusive ZIP aggregate alone is insufficient.

### 4. Possible: Defender/antivirus or another external process amplifies resource-I/O variance

Confidence: **possible, unmeasured**.

The current Java-only diagnostics cannot distinguish slow filesystem service from antivirus scanning or another process consuming storage/CPU. No evidence shows Defender as the owner, so it must not be named causally.

Separating measurement:

In the same single run, capture process-level CPU and I/O ownership for `MsMpEng.exe`/other active processes via the host trace. Do not disable Defender and do not change exclusions. Evidence requires temporal overlap between the enlarged Minecraft interval and external CPU/I/O ownership.

### 5. Possible: thermal/power-state throttling

Confidence: **possible, currently unsupported**.

A long sequence on an old laptop can change frequency/thermal state, but the non-uniform phase shape means a simple global speed multiplier is already insufficient. Neither Java MXBeans nor current BootOptim logs expose reliable package temperature/effective clock data on this Windows target.

Separating measurement:

Observe, do not modify, effective CPU frequency/throttling/power-source state in the same bounded host trace if Windows exposes it. A monotonic fall in effective clocks aligned with the growing phase would support this lane; absent such telemetry it remains unproven.

### 6. Possible but secondary: software-render/backend native tail

Confidence: **possible for late upload/present; rejected for the measured shader errors and for pre-upload atlas preparation**.

PR #138 source-level ordering shows the large atlas/sprite preparation future occurs before stock atlas upload. Therefore Microsoft Basic Render Driver/Mesa cannot directly explain that pre-upload preparation wall as GL upload. The backend can still matter during later apply/upload/presentation and indirectly by consuming scarce CPU.

Separating measurement:

Only a first-title render/present boundary plus process CPU/native host trace can classify a late residual. Do not infer GPU from low Java CPU alone.

### 7. Confirmed methodological risk, controlled by validity gate: Prism/launcher/process inheritance

Confidence: **confirmed for the stale-JVM outlier; not established for normal valid runs**.

Prism itself is not blamed for the remaining 363-423 s spread. The confirmed failure mode is a Java process surviving the intended launch boundary. The appropriate product response is benchmark validation, not changing the user's launcher.

## Why no new BootOptim runtime diagnostic is added in this PR

The low-noise Java measurements requested by this problem already exist on active or completed diagnostic branches:

- #113: process CPU / GC / heap / available-memory snapshots around preload and title;
- #130: absolute JVM start and uptime for stale-process invalidation;
- #138: monotonic reload/ModelManager boundaries with cumulative process CPU;
- #140/#141: bounded ZIP enumeration and correlation with reload preparation;
- #118/#120: shader/native-tail interpretation limits and proof that wall-minus-CPU is not an attribution method.

Adding another in-JAR sampler would duplicate these probes while still failing to observe the missing discriminators: Windows runnable/descheduled time, effective CPU frequency, hard faults/page reads, storage queue/latency and external-process ownership. Those are host-level facts. A Java-only snapshot cannot rigorously separate scheduler vs Defender vs paging vs native waits.

Decision: **NO-GO on a new BootOptim runtime probe for P0.2.** Keep runtime instrumentation opt-in and narrow; use one bounded host-observation run only if the agent principal needs the remaining ranking resolved.

## Minimal one-run physical protocol

Do not run an A/B and do not reboot/purge caches. One fixed current-integration configuration is enough because the goal is classification.

Validity requirements:

1. Archive integration/JAR SHA, effective JVM flags, exact resource selection/order and launcher configuration.
2. Record the intended launch timestamp/PID. Reject the run if JVM start/uptime shows a pre-existing process or unexpected pre-launch age.
3. No log polling while Java is running. Collect logs and reports only after exit/title automation completes.
4. Preserve Windows/Java/driver/Defender/power settings exactly as found. The run observes state; it does not tune it.
5. Collect a bounded host trace/counter set for the single launch: per-process CPU and I/O ownership, Minecraft hard faults/page reads, system paging/file I/O, effective CPU frequency/throttling when available, and process lifetime. Avoid high-cardinality file-name stacks unless the coarse counters still cannot classify the interval.
6. Keep the existing monotonic BootOptim/JVM phase markers. Classify each enlarged interval by wall, process CPU/core-equivalents, GC delta, memory/hard-fault activity and host ownership. Never sum overlapping task-sums.

Decision table:

| Slow interval observation | Supported classification | Not proven |
| --- | --- | --- |
| high wall + high Minecraft process CPU | CPU/work contention / scarce-core scheduling | exact hot method or thermal cause |
| high wall + low Minecraft CPU + hard faults/page reads | memory/page-cache/paging contributor | Defender unless external owner correlates |
| high wall + low Minecraft CPU + external process CPU/I/O | external contention; name process only with ownership evidence | BootOptim code cause |
| high wall + falling effective clock/throttle signal | thermal/power-state contributor | exact software hotspot |
| late render->present wall + low Java CPU | native/render/present bucket | GPU specifically without native evidence |
| slow ZIP inclusive aggregate alone | storage-sensitive subphase | equivalent TTMM saving |

## Product decision

Do not request another optimization until one of the unresolved buckets owns a material **critical-path wall** interval in a valid run. Current evidence supports benchmark hygiene plus one classification trace, not scheduler tuning, Java/Windows changes, driver workarounds, cache purges, Defender exclusions or another concurrency patch.

## Relevant PRs

- #111 — full-resource physical variance and serial partitions.
- #113/#115 — fixed-setting process/CPU/GC/memory evidence.
- #118/#120 — shader/Voxy and post-preload tail interpretation.
- #126 — ModernFix lease audit showing non-reproducible physical drift.
- #130 — stale JVM-age invalidation.
- #138/#141 — resource-reload boundaries and physical ZIP/page-cache sensitivity.
- #140 — FilePackResources enumeration diagnostic.

# Vanilla Bootstrap variance boundary — 2026-09-06

Status: **ACTIVE DIAGNOSTIC / DO NOT MERGE AS PRODUCTION**

Base refreshed from the repository before changes: `agent/integration-current` @
`145c10c2f8132b21e7b7be067c56513b394ccb5a`. The task prompt named
`8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`; GitHub comparison shows that commit
is an ancestor 15 commits behind the live integration branch, so this diagnostic
starts from the live branch as required by `AGENTS.md`.

Related history: PR #15 (pre-entrypoint JFR), #18 (vanilla Bootstrap/Blocks wall),
#19 (rejected class prewarm), #65/#69 (broad slow-hardware campaign), #99 (FML
lifecycle), and #111/#113/#115 (physical phase/wait variance). This probe does
not repeat their continuous samplers, per-resource timing, JFR, or FancyMenu
snapshots.

## Correcting the boundary name

The physical logs have informally called the large interval “ModernFix bootstrap.”
That wording is misleading. In ModernFix's public 1.21.1 source, the
`feature.measure_time.BootstrapMixin` injects a `Stopwatch` into
`net.minecraft.server.Bootstrap.bootStrap()`: it starts after the first write to
`Bootstrap.isBootstrapped` and stops at method return, then logs `Vanilla bootstrap
took ... milliseconds`.

Modrinth identifies the exact pack version as `5.27.14+mc1.21.1` for NeoForge
1.21.1. GitHub history for the 1.21.1 `BootstrapMixin.java` path shows no later
source edit after the 2025-12-27 source-tree migration commit, so the public
1.21.1 implementation is applicable to this version's timing semantics.

Therefore a physical outlier of about **15.84 minutes** in that ModernFix line is
not evidence that ModernFix itself spent 15.84 minutes bootstrapping. It is direct
wall-clock evidence that vanilla `Bootstrap.bootStrap()` occupied that interval
while ModernFix observed it. ModernFix can still participate in transformation of
classes used by Bootstrap, but the timer is an observer boundary, not exclusive
ModernFix self-time.

Normal physical values supplied for the same line are about 1.55–1.84 minutes.
The outlier therefore adds roughly 14.0–14.29 minutes of wall inside the vanilla
Bootstrap boundary. That magnitude is comparable to the overall TTMM excursion to
about 1273 seconds, so Bootstrap is the first boundary to falsify before blaming
later FML registry/reload/FancyMenu work. This is magnitude consistency, not a
paired causal A/B or a claim that every excess TTMM millisecond is Bootstrap.

## Existing evidence inside and around the boundary

PR #18 already established on a fast hosted/reference run that
`Bootstrap.bootStrap()` was 4,239.26 ms wall, `FireBlock.bootStrap()` 3,137.71 ms,
and `Blocks.<clinit>` 2,884.64 ms. Its JFR precursor saw 2,325 class-load events
under FireBlock, about 995 ms summed ClassLoad duration, with many samples under
class transformation. That proves class loading/transformation is real work inside
Bootstrap, but it does not establish why one Windows run stretched by ~14 minutes.

PR #19 then falsified generic parallel non-initializing prewarm as an optimization:
200/200 classes preloaded successfully, yet discovery→entry regressed from 8,001
to 8,177 ms and TTMM from 14,355 to 14,515 ms in that campaign. This does not
exclude class transformation/JIT as a variance cause; it only rejects moving a
fixed hot class list earlier as a speedup.

Current integration already emits transformation-service construct/initialize/
onLoad and root/dependency discovery boundaries before the regular mod constructor.
The BootOptim `mod_entrypoint` marker is the first statement in its `@Mod`
constructor. PR #99 separately measures later FML gather/registry work and found
multi-second critical windows, including a 7.54 s registry initialization and a
6.50 s `minecraft:block` RegisterEvent in one exact-pack run. Those are important
startup costs but cannot explain a 15.84-minute ModernFix `Vanilla bootstrap took`
line because that line directly brackets an earlier vanilla method.

## Prioritized causal map

### 1. CPU/class transformation/JIT inside Bootstrap

This is the highest software-path hypothesis because PR #18 already located
`Blocks.<clinit>` and transforming class loads inside the normal Bootstrap wall.
The falsifier is not a class-count reduction: compare Bootstrap wall with process
CPU, current-thread CPU, loaded-class deltas and JVM compilation-time delta.

- Wall and process CPU both exploding with roughly normal class/JIT work volume
  supports severe CPU starvation/contention or extremely slow transformation work.
- A large increase in loaded-class or JIT work volume supports a genuinely
  different software/classloading path.
- Large wall with low process CPU falsifies a primarily CPU-bound explanation and
  moves the investigation to off-CPU I/O/scheduling.

Process CPU is inclusive across JVM worker/compiler/GC/native threads and is not
exclusive Bootstrap algorithm CPU.

### 2. Windows storage/page cache / antivirus or other file-I/O stalls

Class transformation necessarily causes class bytes to be obtained from JARs, so
storage can surface inside the vanilla Bootstrap timer even though root mod
*discovery* has already completed. This remains a hardware/OS hypothesis, not an
inference from `wall - CPU`.

The cheap probe can only nominate this lane: a very large Bootstrap wall with low
process CPU, low GC time, and similar class/JIT deltas means the process spent most
of the boundary off CPU. It does not prove disk or page faults. If that signature
appears, the next discriminating measurement is one bounded Windows-native trace
(ETW/WPR or equivalent) over a naturally occurring run, covering file I/O/page
faults plus thread scheduling. Hosted Linux exact-pack cannot accept or reject a
Windows page-cache/antivirus mechanism.

### 3. GC / memory pressure

The probe records summed GC collection count/time, heap used at both boundaries,
and free physical memory at both boundaries. A large GC-time increase during the
same outlier would support this lane; ordinary GC totals during a 15.84-minute wall
would strongly lower its priority. MXBean collection time is not exact STW time,
and two memory snapshots do not capture peaks or hard faults.

### 4. External scheduling / system contention

Low Java process CPU with low GC and ordinary class/JIT deltas is also compatible
with descheduling by other Windows activity. This is intentionally not collapsed
into “disk”: Windows-native CPU-ready/scheduling plus I/O/page-fault evidence is
needed to distinguish scheduler starvation from storage waits.

### 5. Root/dependency discovery and BootOptim scan cache

These remain separately measurable with existing early-service/root/dependency
markers, so no new profiler is justified. If an anomalous run shows those existing
windows near their normal values while vanilla Bootstrap alone becomes enormous,
mod discovery is falsified as the cause of the Bootstrap jump. Hosted warm/cold
runs can characterize software discovery behavior, but fresh hosted VMs do not
reproduce the laptop's Windows page-cache state.

### 6. “ModernFix bootstrap overhead” as the direct cause

**Discarded as currently phrased.** The observed log line is a stopwatch around
vanilla `Bootstrap.bootStrap()`, not an exclusive timer for ModernFix code. A
specific ModernFix transformer/mixin could still be implicated later by stacks or
CPU attribution, but the 15.84-minute number itself cannot be assigned to
ModernFix self-time.

## Minimal diagnostic

Property, default off:

```text
-Dboot_optim.bootstrapVarianceDiagnostic=true
```

The diagnostic adds only HEAD/RETURN callbacks to stock
`Bootstrap.bootStrap()`. It does not redirect/cancel the method, enumerate files
or classes, sample periodically, start JFR, force GC, change executors, or log per
class/file. It takes exactly two aggregate snapshots.

The measured target wall starts after management-bean setup and the first snapshot
and stops before the final snapshot. `probe_setup_ms` is emitted separately so a
slow first MXBean initialization cannot contaminate the Bootstrap wall being
attributed.

Marker fields:

- `wall_ms`: target Bootstrap elapsed wall;
- `caller_cpu_ms`: current Bootstrap-calling thread CPU when available;
- `process_cpu_ms`: whole-process CPU delta, inclusive upper bound;
- `gc_count_delta`, `gc_time_ms`: JVM GC MXBean deltas;
- `loaded_classes_delta`, `total_loaded_classes_delta`: class-loading work volume;
- `jit_ms_delta`: JVM-wide compilation-time delta;
- heap/free-physical-memory boundary snapshots;
- uptime boundary values and probe setup wall.

The probe does not enable thread CPU timing if the JVM has it disabled, preserving
JVM state. A `-1` field means unavailable rather than zero.

## Gate and decision

First gate is one hosted exact-pack smoke solely for hook/safety validation:
reaching the title screen, exact resource contract intact, zero BootOptim Mixin
failures, and `BOOTOPTIM_BOOTSTRAP_VARIANCE status=ok`. Hosted absolute Bootstrap
wall is not a physical variance result.

Do not request repeated laptop A/B. If a future naturally justified Windows run
uses this diagnostic, the decision is:

1. process CPU/GC/class/JIT explains the inflated wall -> follow the corresponding
   JVM/software lane with a narrow source-level probe;
2. wall inflates while those JVM work measures stay ordinary -> one bounded
   Windows-native scheduling + file-I/O/page-fault trace is justified;
3. Bootstrap does not inflate while TTMM does -> close this lane and use the
   existing FML/reload/FancyMenu boundaries to locate the different excursion.

No performance optimization or TTMM improvement is claimed by this branch.

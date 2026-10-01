# Ferrite quad hash arithmetic — 2026-10-02

Status: **ACTIVE, hosted semantic gate passed; actual-method replay next; no demonstrated game saving**.
User selected point 5 of the current planning list (original shortlist point 8).
Authority refreshed to `agent/integration-current@411e17cbfe4739cdcab14bf14321c7901cb1c775`.
Isolated branch `codex/ferrite-quad-internals-20261002`. No physical instance,
laptop controller, frozen Decocraft campaign, or production branch was changed.

## History and actual source

Read open/closed #298/#308/#315/#319 bodies/comments and integration's
owner-candidate and final presize decisions. Persistent empty storage and late
presizing remain **retired**. This proposal changes arithmetic only, not table
capacity, allocation scheduling, trimming, clear, generation lifetime or mutexes.
No integrated Ferrite optimization or overlapping active hash candidate exists.

Installed FerriteCore 7.0.3 JAR SHA-256:
`D87EA28262715EBFF45B8A82D493E6B468E7A4521BC021DF5D88302196D030A8`.
Read-only bytecode authority and earlier exact fastutil 8.5.12 disassembly:
`C:/BootOptimBench/analysis-reload-20261001/FerriteDeduplicator.txt`,
`ObjectOpenCustomHashSet.txt` and the actual installed JAR in that directory.

`Deduplicator.deduplicate` holds the existing set monitor, obtains the current
vertex array, invokes `addOrGet` once and installs its canonical result. For a
normal insertion `addOrGet` hashes the input once, then probes and performs
strategy equality as needed. The equality strategy is `Arrays.equals`, which
already preserves identity/length/content checks and permits JVM intrinsics.
No mandatory second full hash per normal insertion was found. Growth rehashes
stored keys; that repetition is the already-studied storage front, not new work.

The private `betterIntArrayHash` starts at zero and computes
`h = 31*h + HashCommon.murmurHash3(value)` for every current integer. Array
contents are publicly mutable; persistent identity-hash memoization would omit
current reads. Changing the hash function could change probe/iteration behavior
and behavior with externally mutated keys. Neither is this candidate's premise.

## Changed arithmetic premise

Keep every input read and murmur mix, but maintain four independent polynomial
accumulators with multiplier `31^4 = 923521`, then combine them with
`31^3 = 29791`, `31^2 = 961`, `31`, `1`. Process the remainder using the original
recurrence. Integer overflow is arithmetic modulo 2^32 in both formulas. This
removes part of the serial dependency without changing a single hash bit or
retaining arrays/hashes. It does not remove comparisons, callbacks or locking.

The experimental optional Mixin only accepts the pinned reported version 7.0.3
and 32-int arrays. Unknown/absent versions and other lengths run stock. Both
`boot_optim.ferriteStripedHash` and verification default false. Diagnostics are
not intended for integration merely because build or smoke passes.

Verification (`boot_optim.ferriteStripedHashVerify=true`) leaves the actual
original method running and compares its RETURN with the helper. A mismatch
fails this diagnostic; markers at checks 1 and 8192 prove actual activation.
Verification does twice the arithmetic plus atomic counting and is NEVER a
timing population. Normal candidate mode has no per-hash timers/counters.

## Offline evidence and its limits

`tools/reload-bench/FerriteHashArithmeticAudit.java` checks the exact proposed
helper against the recurrence transcribed from bytecode, for lengths 0..256,
random and extreme constant integers, null failure, current values, duplicate
identity, three clear/trim generations and table iteration. **575,028 composite
hash/canonical checks pass**, including real fastutil canonical representatives.
The separate expanded four-word expression also matches but supplied no stable
performance premise; it is not the runtime candidate.

Physical fast-PC Java 21.0.4, synthetic 32-word random and quad-like corpora:
raw exploratory logs in `C:/BootOptimBench/analysis-reload-20261001/ferrite-hash-arithmetic/`.
Origin/endpoint of each CPU block are synchronous replay entry/return on the
same thread. Corpus construction, semantic checks and warmup are excluded.
Each block has 16,777,216 hash calls and equal checksums in C/B/B/C order;
three such groups per data domain. Windows thread CPU quantization is 15.625 ms.

Initial repeated-corpus replay has extremely unstable CPU, including implausibly
short later blocks; it cannot justify performance or extrapolation to 1.25M
actual insertions. A later checksum-dependent offset/volatile round boundary
still shows substantial drift. Preserve those raw results as inconclusive,
not a headline win or proof of zero benefit. They are synthetic, not actual
reload owner timings, and provide no critical-path or hardware-general claim.
No synthetic delta is grounds for production or physical campaign dispatch.

Package build passes locally (`gradlew build`, Java 21); distributable is the
bootstrap JAR. The checker compiles the proposed helper directly, avoiding a
separate candidate transcription. This does not prove actual Mixin execution.

## Finite decision gate

1. Hosted semantic smoke with candidate+verification, expected active version,
   at least 8192 actual comparisons, valid selected packs, successful menu,
   no mismatches/Mixin failures. Ordinary absent-mod startup must pass too.
2. If semantic gate passes, actual transformed pack-array replay or outer
   owner measurement in two controls/two candidates, including injection cost;
   use clock resolution/CPU plus wall and reject hoisting/unstable workloads.
   No million per-hash clocks. Whole-reload/GC observations are indirect and
   are never substituted for the optimized owner's CPU.
3. Retire if no coherent owner benefit. If benefit exists, prepare an
   uninstrumented promotion, physical/visual gates as relevant, catalog and
   refreshed integration review. No indefinitely disabled runtime feature.

JEI was delegated separately to a native bounded subagent. Its concrete edge-map
clone-skipping proposal is a semantic NO-GO because callback traversal order
changes. That evidence lives in the separate JEI audit branch/PR.

## Hosted semantic result and bounded next gate

PR #322 source `8a3fc71c`: Build and absent-mod client startup pass.
Exact-pack [36928578223](https://github.com/wachipayox/BootOptim/actions/runs/36928578223)
passes. Raw artifacts `C:/BootOptimBench/analysis-reload-20261002/ferrite-hash-semantic-36928578223`.
Actual target logs `active version=7.0.3 verify=true`, comparison markers 1 and
8192 with zero mismatch. Original method remains enabled and every eligible
RETURN is compared, not only marker calls. Menu reached, zero BootOptim Mixin
errors; ordered pack selection valid, blocks atlas 8192x8192 levels 2. The
90,609-ms menu diagnostic observation is NOT a performance comparison.

The bounded next diagnostic adapts existing Sodium owner replay instead of
creating another per-call profiler. With `boot_optim.ferriteHashReplay=true`
and verify=false it copies every 2048th eligible actual hash input during the
first generation, capped at 4096 arrays; no mutable original arrays are retained.
After successful reload, overlay removal and two stable menu seconds, it invokes
the **actual transformed private hash method** through one MethodHandle in both
arms. A thread-local override selects stock/candidate, leaving other threads'
mode unchanged. Both arms include adapters, injection guards and callbacks.

First compare every captured input through both actual paths, then equal
alternating warmups, then C1/B1/B2/C2 with approximately 8M calls per block,
same corpus and checksum. CPU and wall clocks surround blocks, never individual
hashes. GC totals accompany observations; clear snapshot references and stop
the diagnostic client in finally. Require >=256 captured arrays, exact supported
target, four observations, equal calls/checksums and finished marker. Capture,
cloning, warmup and rendering/startup time are excluded from replay observations.

This is pack-array replay CPU of the transformed hash, not whole deduplication,
stock world latency, a full hardware population or actual reload critical wall.
The sparse capture includes growth rehash invocations and is scheduling-dependent;
do not use the sampled count to infer original call frequency or multiply the
result into an asserted real-game saving. No actual-owner result exists yet.

## Rejected measurement and repaired shutdown contract

Run36929924845 on e9e6019d passed generic Build/Startup/hosted menu gates but
is **INVALID as replay performance evidence**: zero corpus/observation/finished
markers. Strict `check_ferrite_hash_replay.py` rejects it. Existing
`ClientStartupHooks` called stop immediately at the main-menu opening because
the replay opt-in had not disabled stock benchmark exit. Console confirms
main_menu followed by shutdown, before two stable seconds or any replay.
This is a diagnostic lifecycle defect, not Ferrite performance/correctness failure.
Raw artifacts: `C:/BootOptimBench/analysis-reload-20261002/ferrite-hash-replay-36929924845`.

Repair follows the established Sodium replay contract: keep main-menu marker,
but suppress that immediate stop only when this finite replay opt-in is enabled.
The replay's finally owns stop after either four observations or explicit failure.
Add initial observe/completion markers to distinguish shutdown from missing
reload endpoint. Normal launches and other benchmark auto-exit remain unchanged.
Do not count the generic green status or86.935s startup as a performance vote.

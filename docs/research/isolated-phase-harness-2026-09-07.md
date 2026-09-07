# Isolated phase and mechanism harness

Status: **PROPOSED METHODOLOGY**

The full exact-pack launch is the final integration gate, not the only place where
an optimization should be designed. Most experiments can be moved to cheaper,
repeatable layers if the input fixture, semantic oracle, and measurement boundary
are explicit. This document defines the layers and the point at which a result is
allowed to graduate to a real startup run.

## What can and cannot be isolated

| Area | Cheap isolation | What still requires the real client |
| --- | --- | --- |
| Pure algorithms and data structures | JMH or a small Java benchmark with captured JSON/IDs/arrays | Critical-path leverage in the reload graph |
| Mod discovery / bootstrap codecs | Existing bootstrap JUnit fixtures and synthetic mod JARs | ModLauncher transformation order and third-party service interaction |
| Model/resource algorithms | A replay fixture containing the selected model/blockstate JSON and referenced textures, with stock/candidate adapters | NeoForge reload barriers, registries, custom loaders, and thread scheduling |
| Reload scheduling | A recorded task/dependency graph replay with deterministic executors | The actual `SimpleReloadInstance` barrier and listener callbacks |
| FancyMenu/MCEF | Contract/state-machine harnesses with fake consumers | JNI, CEF subprocesses, OpenGL, window presentation, and visual equivalence |
| Full pack | Hosted exact-pack smoke and 3x3 A/B | Physical laptop storage, native/GPU/audio behavior and final noise |

Isolation is therefore a filter and a way to iterate, not permission to replace
the final gate. A replay that saves milliseconds in a task sum is not a startup
win unless the same work is on the measured critical path.

## Four execution layers

### 1. Algorithm microbenchmarks

Use no Minecraft window, Prism, MCEF, or NeoForge launch. Feed deterministic
captured inputs to the candidate and stock/reference implementation. Examples are
material resolution, indexed blockstate matching, list/bitset operations, plan
compilation, cache key construction, and ZIP-entry lookup. Run enough warmup and
forks to separate JIT effects from the operation under test.

The result must include input fixture hash, Java version, CPU count, warmup/measure
iterations, allocation/CPU counters when available, and a semantic digest. This
layer answers “is the algorithm cheaper?” only.

### 2. Class-level replay fixtures

Extract a bounded, immutable slice of the exact pack: model and blockstate JSON,
parent references, texture identifiers, relevant resource-pack order, and any
loader metadata needed by the mechanism. A replay runner constructs the same
Minecraft value objects where possible, or a deliberately narrow adapter when a
client-only object cannot be created. Stock and candidate runs receive the same
fixture and executor policy.

For model work, compare the observable result rather than only timing: resolved
materials, model parent chains, variant selection, quad counts/order, sprite
identifiers, and failure/fallback decisions. The candidate must fail open for
inputs outside the declared fixture domain. The fixture manifest must state which
vanilla/custom loaders are excluded; exclusion is a limitation, not silent proof
of compatibility.

This is the first useful layer for iterating on ModelBakery/BlockModel mechanisms.
It can run many times per GitHub Actions VM and does not pay the 3–5 minute laptop
launch or native MCEF startup.

### 3. Phase replay and executor experiments

When an optimization depends on scheduling rather than a pure function, record a
minimal phase plan from a diagnostic run: task identity, dependency edges, input
fixture hash, estimated work class, and barrier/turn membership. Replay that plan
with deterministic executors (single worker, two workers, and the candidate policy)
and collect critical-path wall, per-task CPU, queue wait, allocation, and GC data.

The replay must not pretend that overlapping listener durations are sequential. It
must report both task-sum/inclusive time and the reconstructed barrier critical
path. A plan replay can reject a scheduling idea cheaply, but it cannot promote an
optimization by itself: the surviving design needs a real exact-pack smoke and
then an A/B that reaches the same `main_menu` endpoint.

### 4. Full exact-pack and physical gates

The existing hosted workflow remains the integration surrogate. Each A/B entry
runs in a fresh VM, uses unique artifact names, and records the same origin marker
and endpoint. It should be requested only after layers 1–3 show a coherent premise
and semantic equivalence.

The laptop remains necessary for HDD/page-cache, native/GPU/audio, and small noisy
effects. It should receive only candidates that passed the cheaper gates. Its
transactional controller must stage one verified distributable wrapper, verify the
effective Java command line, freeze logs after exit, and restore the original
instance before the next experiment.

## Fixture and result contract

Every isolated run should publish a machine-readable manifest containing:

* integration commit and candidate commit/property;
* exact-pack release/hash or replay-fixture hash;
* Java version, OS/runner image, processor count, executor policy and heap;
* input resource-pack order and selected options;
* stock/candidate semantic digest and mismatch count;
* measurement origin and endpoint, plus CPU/wall/allocation/GC fields;
* whether the result is micro, replay, hosted-startup, or physical-startup evidence.

Artifacts must use the variant and repetition in their name. A missing origin,
stale JVM prefix, mismatched endpoint, semantic mismatch, or incomplete fixture
must make the result `invalid`/`inconclusive`, never a performance win.

## Recommended first implementation

Start with a small `bench` source set (or a separate non-runtime Gradle project)
for the material-resolution and blockstate-index fixtures. Reuse the existing
bootstrap JUnit fixture conventions rather than adding a second production profiler.
Then add one replay for the current ModelManager material-resolution hypothesis.
Only after that runner can prove stock-equivalent outputs should it be connected to
the hosted workflow as a separate `phase-replay` mode. The full exact-pack mode
must remain unchanged and continue to validate the real reload graph.

This ordering gives fast iteration without weakening the project's evidence bar:
cheap isolated rejection first, exact-pack validation second, and scarce physical
laptop runs last.

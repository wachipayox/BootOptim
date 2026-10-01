# Independent Sodium quad-classifier audit — 2026-09-30

Status: **SEMANTIC PASS / SYNTHETIC CPU INCONCLUSIVE**. This audit supplies evidence for the root agent's separate default-off candidate. It does not itself introduce optimization code or modify any instance. No game/laptop runs were performed.

## Baseline and reproduction

Baseline is the unchanged installed Sodium 0.8.12-beta.1 `ModelQuadFlags.class`, SHA-256 `78b5648f4d4b1b76cc0eab4a7bc3df547288a28c2510e02eabc4a81a6dd3a6b6`, from the inner JAR of the pack's Sodium wrapper. The harness extracts that class, its generated enum-switch companion, and the exact `ModelQuadView.class`. Only Minecraft `Direction` and unused `TextureAtlasSprite` dependencies are stubbed. Direction has the real six ordinal/axis mappings. Baseline arithmetic is executed from the actual binary, not copied into Java source. A concrete monomorphic Quad implements both interfaces; there is no dynamic Proxy.

The runner accepts the outer installed wrapper or its inner Sodium JAR, refuses a baseline hash mismatch, and compiles the actual candidate helper source supplied by the caller:

```text
python scripts/sodium-quad-classifier-audit/run.py --sodium-jar <exact-sodium.jar> --candidate-source-dir <directory-containing-SodiumQuadFlagClassifier.java-and-QuadCoordinateView.java> --semantic-only
```

Omit `--semantic-only` for the warmed paired CPU microbenchmark. Requires Python standard library and JDK 21 javac/java on PATH. Extracted binaries and stubs are temporary and are not committed. Audit helper source hashes:

- SodiumQuadFlagClassifier.java: `436fef6685644c5f13e5358f36703410f275aea620aff2aa8ea82c8022fca1de`
- QuadCoordinateView.java: `124628f7af9d72e3993570b1eca79cc5aa873c154c8ac7bc8c068dfdcaf6424b`

## Semantic coverage

Actual helper compared directly with actual baseline: **2,410,296 comparisons, zero mismatches**. Includes all six directions; 400,000 deterministically seeded quads using random raw float bits, with one quarter forced planar; paired normal-coordinate adversaries; special values in every coordinate; sentinel ±32 and neighbouring representable floats; ±infinity, NaNs, signed zero, ±Float.MAX_VALUE; 1e-4 and 0.9999 equality and neighbours. Twelve X/Y/Z getter accesses remain in exact stock vertex order. Seventy-two direction/access-position exception tests verify the same abrupt getter failure position.

An initial independent implementation using `!(minA < eps && ... maxB > upper)` failed on NaN. Root was notified and the actual helper uses the correct stock direct OR comparisons. The semantic test then passed. Parallel equality additionally requires the first plane in [-32,32] because stock min/max start at +32/-32; constant out-of-bounds planes, infinities and NaNs must not be classified parallel. Signed-zero equality remains equivalent for the observed flags.

This proves the pure classifier over tested data, not the optional-mod hook, other transformations, visuals or whole-reload performance. Null directions and invalid ordinal integers are not members of the six-direction input domain. Runtime compatibility remains a separate hosted/visual gate.

## CPU benchmark, not reload timing

JDK Temurin 21.0.4, physical Windows fast PC. Each JVM warms both paths for 16 rounds × 300 repetitions × 8,192 quads, then runs eight paired samples with alternating order. Each measured path processes 8,192,000 calls. Synthetic corpus is 75% planar and 25% arbitrary bounded coordinates, using mixed directions. Time is `ThreadMXBean.getCurrentThreadCpuTime`, not JVM/game wall; Windows returned coarse 15.625 ms increments. No claimed time-to-menu/F3+T saving follows from this fixture.

| Fresh JVM | Stock median CPU ms | Actual-helper median CPU ms | Candidate minus stock ms |
| --- | ---: | ---: | ---: |
| 1 | 382.8125 | 281.2500 | -101.5625 |
| 2 | 289.0625 | 312.5000 | +23.4375 |
| 3 | 273.4375 | 250.0000 | -23.4375 |

Two CPU runs favour the helper and one regresses. Direction is therefore **inconclusive**, despite semantic success. The initial copied-algorithm and wall-clock development numbers are not candidate validation and are excluded. Do not turn these synthetic sample medians into a per-reload prediction. The default-off runtime candidate needs its actual gate before performance acceptance.

## Installed compatibility reference census

A read-only recursive scan of every installed top-level and nested mod JAR searched constant-pool bytes for `net/caffeinemc/mods/sodium/client/model/quad/properties/ModelQuadFlags`. Eight class references were found, all within Sodium itself: FlatLightPipeline, SmoothLightPipeline, ModelQuadFlags and its enum-switch companion, DefaultFluidRenderer, EncodingFormat, QuadViewImpl and BakedQuadMixin. Only the classifier itself and the last two contained both the owner and `getQuadFlags` method name. No other installed mod has a direct bytecode owner reference to this target.

This reduces known direct override risk but is not proof against dynamic/reflected transformers, owner-fragment string construction, or a different pack/version. The candidate's exact version/shape guard and stock fallback remain necessary.

# BlockModel deserializer allocation-growth diagnostic — 2026-09-08

Status: **rejected as an optimization front; diagnostic retained only**.

PR: #186 (`agent52/model-deserializer-allocation-20260908`).
Base authority: `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
Reference real-class replay: PR #183.

## Question

Does the dominant allocation cost inside ordinary `BlockModel.fromStream` deserialization come from collection growth/reallocation that could be removed by pre-sizing from JSON cardinalities?

This is deliberately narrower than ModelManager/TTMM. It does not test sprites, FaceBakery, bake, render-thread work, GL, or persistent model caching.

## Instrumentation

The PR imports the tooling-only real-class replay from #183 and adds opt-in JFR only to the explicit FML `runData` JVM when `BOOTOPTIM_MODEL_REPLAY_JFR_PATH` is set. Normal builds and the packaged mod do not enable it.

`jdk.ObjectAllocationSample` stacks are filtered to allocations whose stack contains the real `BlockModel.fromStream` / vanilla deserializer / NeoForge `ExtendedBlockModelDeserializer` path. The analyzer separates:

- any collection stack;
- actual `ArrayList.grow` / `HashMap.resize`-style growth;
- Gson stacks;
- top allocated classes and top allocation frames.

JFR `weight` is a sampled estimate, **not exact allocated bytes**. Exact current-thread phase allocation remains the replay's `com.sun.management.ThreadMXBean#getThreadAllocatedBytes` delta.

### Clock and CPU scope

For `real_blockmodel_parse`, wall starts immediately before the loop that invokes the real `BlockModel.fromStream(Reader)` for each materialized source and ends immediately after that loop. Wall is `System.nanoTime`; CPU is current-thread `ThreadMXBean` CPU time; allocation is current-thread allocated-byte delta. This is an exclusive replay phase, not inclusive ModelManager/resource-reload wall and not `main_menu` TTMM.

The scaled JFR records the hosted runner CPU brand as **AMD EPYC 7763 64-Core Processor**. JFR reports a virtualized topology with four hardware threads exposed to the recording; do not compare this topology to the physical i3-2350M.

## Sanity run — bounded #183-sized fixture

Workflow run: `34175152459` (`Model Deserializer Allocation Diagnostic` #1).
Artifact: `10037007948`, artifact digest `sha256:5b8118533c8af1764642936a0d9e4913dce702604c6c491a8142a803571db88d`.

The 24-root replay retained #183's semantic digest:

`502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050`

First parse pass under JFR instrumentation:

- 24 parse operations;
- 42.136 ms wall;
- 34.091 ms current-thread CPU;
- 8,848,312 current-thread allocated bytes.

Only ten JFR allocation samples were attributable to the parse path, so this run was not used to close the hypothesis. It nevertheless observed **zero** growth/resize samples.

## Scaled real-resource run — 4096 roots

Workflow run: `34175339981` (`Model Deserializer Allocation Diagnostic` #2).
Head: `1dbef87bb4a09978e96273e1deb838510cdac86c`.
Artifact: `10037080435`, artifact digest `sha256:cbf716aa378f004797aced8911d0d1db170cf62f3faf840d7f75a6debc9a766a`.

Pinned fixture: `exact-pack-2026-09-02-v1`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

Fixture enumeration/materialization:

- 35,384 model candidates;
- 32,221 logical model resources;
- 4,096 selected roots;
- 4,103 resources after parent-closure materialization;
- replay semantic output contains 4,155 resolved models, 28,664 elements and 145,324 faces.

The enlarged fixture has a different expected semantic digest because its root set is intentionally different: `72bae518ab44c0fb6381bef101b1114a51964bb23a22e211edb6781a9ab99fe3`. Stock repeat and identity-candidate semantics remained equal and the deliberate semantic fault remained detected.

### Real parse phase

| replay pass | wall ms | current-thread CPU ms | current-thread allocated bytes | successful source parses |
|---|---:|---:|---:|---:|
| stock-1 | 958.697 | 853.025 | 1,294,976,104 | 4,101 |
| stock-2 | 410.981 | 298.383 | 1,278,722,408 | 4,101 |
| identity-candidate | 532.095 | 414.256 | 1,278,255,184 | 4,101 |

The large cold/warm wall difference is one reason this diagnostic does not convert replay time directly into a startup-saving claim.

### Allocation-stack attribution

Across the JFR recording, 567 weighted allocation samples were attributable to the real parse path.

- parse-path JFR sample weight: 3,444,153,170 bytes;
- collection-stack sample weight: 115,227,432 bytes (**3.346%**);
- growth/resize sample weight: 90,069,603 bytes (**2.615%**), 8 samples;
- `HashMap.resize` attributable samples: **0**;
- all eight growth samples: `java.util.ArrayList.grow`.

Critically, every observed `ArrayList.grow` stack is the same Gson-tree path:

`ArrayList.grow -> JsonArray.add -> TypeAdapters$28.read -> Streams.parse -> TreeTypeAdapter.read -> GsonHelper.fromJson -> BlockModel.fromStream`

So the measured growth is **not** a vanilla `BlockModel` element-list, face-map, texture-map or NeoForge ordinary-model collection repeatedly resizing after the JSON cardinality is already known. It is Gson building the intermediate `JsonElement` tree before `ExtendedBlockModelDeserializer` / vanilla deserialization consumes it.

The dominant weighted allocation frames were instead Gson/reader/tree temporaries, especially `JsonReader` buffer construction and `JsonTreeReader` state arrays. Representative top weighted object classes were `char[]`, `int[]`, `Object[]`, `String[]`, Gson tree objects/nodes and numeric/string temporaries. These are not removable by a small `ensureCapacity(elements.size())`-style patch in the ordinary model deserializer.

JFR sample weights are not comparable one-for-one to the single-pass current-thread allocated-byte counter: the recording spans repeated replay passes and parent work, and allocation sampling is weighted/statistical. The percentage distribution is used only as attribution evidence.

## Semantic/error gates

Both diagnostics retained the real #183 class path. The 24-root sanity run retained the reference digest exactly. The 4096-root run retained internal stock-repeat/identity equality and fault detection.

Controlled probes still observed:

- malformed JSON -> `com.google.gson.JsonSyntaxException`;
- missing parent -> resolver path followed by `java.lang.IllegalStateException` (`BlockModel parent has to be a block model.`);
- parent cycle -> both cycle parent requests observed and the same real `IllegalStateException` path;
- no downstream lowering was attempted after the cycle probe.

## Decision

**Reject collection pre-sizing as the next optimization. Do not open a production/candidate PR.**

The hypothesis was that collection growth/reallocation might dominate the ~allocation-heavy real parse phase. On 4,096 exact-pack roots it is only 2.615% of attributable JFR sample weight, and the observed growth is inside Gson's intermediate JSON-tree construction rather than a model collection that can be safely pre-sized from an already-available cardinality.

A candidate that tried to remove this growth would need to alter or replace Gson tree construction, pre-scan the JSON before the real parser, or otherwise change a much broader parser boundary. That is not the minimal cardinality pre-sizing candidate requested here and would introduce semantic/error/unknown-field risk plus duplicated work.

Because the replay gate rejected the mechanism, **no hosted exact-pack `main_menu` A/B was run and no physical laptop run is warranted**. The gate order is intentional: an A/B or laptop run is for a concrete mechanism that survives semantic/microbenchmark diagnosis, not for proving that a non-material allocation category has no effect.

This does not say parse cost is unimportant. It says the specific source of cost proposed here — avoidable backing-collection growth from ordinary model cardinalities — is not dominant.

## Reopen criterion

Reopen only if a future real-class recording on at least ~4,000 exact-pack roots shows one of these:

1. `grow`/`resize` is at least 10% of parse-path JFR weighted allocation in two independent hosted JVMs, **and** the stack is below the vanilla/NeoForge model deserializer rather than Gson tree construction; or
2. exact allocation instrumentation identifies a specific ordinary-model collection whose backing storage repeatedly grows and whose final cardinality is already available without a pre-parse/second JSON pass.

Any reopened candidate must still be default-off/fail-open, preserve order, exceptions, floats/UV/parents and unknown-field behavior, retain the semantic digest for the same fixture, then pass hosted exact-pack `main_menu` A/B with at least three repetitions before any laptop test.

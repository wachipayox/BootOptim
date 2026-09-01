# Atlas sprite decode decomposition — 2026-09-01

Status: **PROFILER READY / EXACT-PACK DECOMPOSITION PENDING**

This research lane follows PR #69's exact-pack finding that sprite loading, not geometric atlas packing, dominates the blocks-atlas preparation tail on the slow laptop. This document belongs to the diagnostic branch `agent/profile-atlas-decode-decomposition`; it is not a production optimization and must not be merged merely because CI is green.

## Integration baseline and preserved production behavior

The diagnostic branch was created from refreshed `agent/integration-current` SHA `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`.

The existing Decocraft 3.0.11 quarter-turn geometry reuse remains production behavior and is intentionally untouched. Its bake-lifetime cache, strict class/model guards, NeoForge quad metadata preservation and fail-open behavior remain authoritative. Atlas decode work must not be used as a reason to remove or weaken that optimization.

## Exact-pack evidence entering this campaign

PR #69 measured on the slow exact-pack laptop:

- all `SpriteResourceLoader.loadSprite`: `20,054` calls, `65,326.153 ms` task-sum, `3.258 ms` average, `422.158 ms` maximum;
- Decocraft: `5,771` sprites, `40,553.154 ms` task-sum, `7.027 ms` average, `395.541 ms` maximum;
- Decocraft therefore contributes about **62.1%** of the measured sprite-load task-sum while representing about **28.8%** of calls;
- other large namespaces: dndecor ~`4,028 ms`, minecraft ~`3,337 ms`, createfood ~`2,838 ms`, tfmg ~`2,633 ms`, buildersdelight ~`1,747 ms`, furnish ~`1,684 ms`.

For the blocks atlas:

- `loadAndStitch`: `55,377.455 ms` wall;
- final regions: `19,732`;
- supplier discovery: `943.057 ms`;
- final `SpriteLoader.stitch(...)`: `875.884 ms`.

The final geometric stitch is therefore only about **1.58%** of blocks-atlas `loadAndStitch` in that run. Small atlases whose futures complete 14–17 s later are not independent 14–17 s costs; they share the same resource-worker tail and their wall times must not be added.

The earlier whole-startup JFR also found Decocraft's JAR at `3,603` file-read events / `11.8 MB`, with `FileChannelImpl.implRead` contention visible on resource workers. That is strong evidence that storage/JAR traversal is real on the weak machine, but it does not by itself prove that physical I/O rather than ZIP inflate or PNG decode dominates `loadSprite`.

## Exact NeoForge 1.21.1 load path

NeoForge 1.21.1 patches vanilla `SpriteResourceLoader` only at the constructor hook boundary: the default loader still obtains/copies resource metadata, opens the resource stream, calls `NativeImage.read`, reads animation metadata/calculates the frame size, validates the frame dimensions and finally calls the supplied `SpriteContentsConstructor` instead of directly constructing `SpriteContents`.

The important ordering is:

1. `Resource.metadata()` and `ResourceMetadata.copySections(...)`;
2. `Resource.open()`;
3. `NativeImage.read(InputStream)`;
4. animation metadata lookup + `calculateFrameSize` + dimension validation;
5. NeoForge `SpriteContentsConstructor.create(...)`.

Resource-manager lookup/override selection happens before `loadSprite`: the method receives the already-selected `Resource`. The new profiler therefore reports lookup as **outside this method** and measures only the `Resource.open()` boundary inside it. Resource-pack override order is not changed.

There is **no mipmap generation in `SpriteResourceLoader.loadSprite`**. Mipmap work occurs later in atlas preparation/upload paths and should not be charged to the sprite-load decomposition.

### `NativeImage.read` split

Minecraft 1.21.1's `NativeImage.read(Format, InputStream)` first calls `TextureUtil.readResource(InputStream)` to materialize encoded PNG bytes in a native `ByteBuffer`. It then calls the ByteBuffer overload. That overload validates the PNG header and calls `STBImage.stbi_load_from_memory`.

For this campaign the non-overlapping decomposition is:

- metadata call;
- metadata copy/residual before stream open;
- resource stream open;
- resource-stream reads;
- encoded-byte staging/copy (`TextureUtil.readResource` minus delegated stream-read time);
- PNG header validation;
- STB PNG decode;
- small ByteBuffer-decode wrapper residual;
- `NativeImage` outer cleanup/residual (encoded buffer free/stream close/etc.);
- animation/frame-size work;
- NeoForge `SpriteContentsConstructor`;
- unaccounted `loadSprite` residual.

STB's single native decode call contains PNG chunk parsing, PNG DEFLATE, filter reconstruction/color conversion and allocation of the decoded native image. Those operations cannot be separated reliably from Java without native STB instrumentation. The profiler therefore reports that unit as `stbi_decode`; decoded output allocation is explicitly part of that number rather than being mislabelled as a separate Java allocation.

## Diagnostic implementation

The branch adds three narrow mixins plus `AtlasDecodeProfiler`:

- wraps the authoritative `SpriteResourceLoader` passed to `SpriteLoader.runSpriteSuppliers` and delegates it unchanged;
- wraps the supplied NeoForge `SpriteContentsConstructor` only to time the call, preserving null/skip/custom subclass semantics;
- observes `Resource.metadata()` and `Resource.open()` only while a sprite load is active;
- wraps only the returned sprite InputStream and records delegated read calls/bytes locally in the worker context;
- decomposes `NativeImage.read` at `TextureUtil.readResource`, PNG-header and STB boundaries;
- emits no per-sprite log lines and keeps no global top-N lock;
- emits one final aggregate at first title-screen opening.

A reload-scoped concurrent key set records whether the same `(sourcePackId, ResourceLocation)` is requested again. Its repeat task-sum is an **optimistic bytes-cache ceiling**, not a proposal to cache `SpriteContents`: constructor identity/custom semantics are deliberately not assumed shareable.

The profiler also records the original stream implementation class and exact decoded dimensions. For Decocraft it emits the most common dimension pairs, total encoded PNG bytes read, total decoded RGBA bytes and their ratio. That will answer whether the 5,771 Decocraft sprites are unusually large, unusually poorly compressed, or simply numerous/expensive to reach and decode.

## Physical I/O versus ZIP/JAR decompression caveat

At the Java `InputStream` boundary, one delegated `read` can include filesystem wait, UnionFS/ZIP machinery, DEFLATE CPU and scheduler descheduling. The profiler measures both wall and current-thread CPU time for those reads:

- `stream_read_ms`: complete resource-stream read wall time;
- `stream_cpu_ms`: CPU consumed on that worker while the read call executes;
- `stream_non_cpu_ms = max(wall - CPU, 0)`.

`stream_non_cpu_ms` is **not pure physical-disk time** on a four-thread machine: descheduling/contention is included. It is nevertheless the closest low-overhead in-process ceiling for non-CPU resource waiting and can be cross-checked with the JFR `FileRead`/`FileChannelImpl.implRead` evidence. `stream_cpu_ms` includes JAR/ZIP inflate plus stream machinery; encoded staging/copy outside the delegated read is reported separately.

A production decision must not claim an exact physical-disk percentage unless the exact-pack run is paired with lower-level JFR/OS I/O evidence. The research deliverable should report both the conservative `resource stream (I/O + ZIP)` share and the `non-CPU wait` share.

## Decocraft resource facts established before the new run

The exact published NeoForge file is `decocraft-3.0.11-1.21.1-neoforge.jar`; Modrinth/CurseForge list it at about `88.9 MB`. It is an all-rights-reserved distribution. Existing BootOptim source inspection identifies the production geometry classes as Decocraft's `BBModelGeometryLoader$BBGeometry` and `BlockbenchModel`, but this campaign does not assume the geometry hotspot explains texture cost.

The exact-pack runtime evidence currently establishes:

- `5,771` Decocraft sprite loads;
- about `40.553 s` sprite-load task-sum;
- `7.027 ms` average versus `3.258 ms` for all sprites;
- `3,603` lower-level JFR reads / `11.8 MB` attributed to the Decocraft JAR in the earlier whole-startup recording.

It does **not yet establish** the PNG dimension distribution, encoded PNG byte total, per-entry JAR compression ratio, repeated resource-key rate, or open/read-vs-STB split. Those values are exactly what the new aggregate is designed to collect; they must not be guessed from the JAR's total size.

## Ceiling questions to compute from the exact-pack aggregate

### Bytes already available

Optimistic task ceiling = resource open + stream-read + encoded staging/copy removed while keeping metadata, STB decode, animation and constructor authoritative. This is the maximum benefit a perfect reload-scoped encoded-byte provider could expose before overlap/critical-path correction.

### Pure PNG decode

Optimistic task ceiling = PNG header + STB decode + decode-wrapper work removed while keeping resource access and constructor behavior unchanged. This identifies whether a different decoder/decoded cache could matter at all.

### Reload-scoped encoded cache

Use `repeats` and `repeat_task_ms` only as an upper bound. A valid implementation would cache immutable encoded bytes by the already-resolved resource identity/source for the duration of one reload, not by namespace alone. It must not bypass resource-pack override selection or reuse a `SpriteContents` across constructor identities.

### Read-ahead / batching by JAR

A GO requires the Decocraft aggregate to show meaningful stream/open/non-CPU time and low enough decode dominance that storage locality can affect the worker tail. Any implementation must preserve the selected `Resource` and its pack precedence; batching is about when bytes are read, never about choosing a different resource.

### Persistent decoded cache

Research only. A safe design would need a fingerprint covering at minimum:

- exact selected resource bytes/content hash;
- resource-pack stack/order and selected source identity;
- Minecraft/NeoForge and BootOptim cache schema;
- metadata bytes/sections that affect animation/frame interpretation;
- requested `SpriteContentsConstructor` semantics or a restriction to the vanilla/default constructor;
- decoder/output format assumptions.

A cache hit must still preserve custom loaders, constructor/null semantics, animation metadata and resource reload invalidation. GPU/OpenGL texture objects are never persisted or created on background threads.

## Compatibility surface

Must preserve:

- all custom atlas/sprite source loaders;
- NeoForge `SpriteContentsConstructor`, including custom `SpriteContents` and null-to-skip behavior;
- metadata parsing/error behavior;
- resource-pack override order and reload invalidation;
- animation metadata/frame sizing;
- image close/free ownership on failures;
- later mip generation semantics;
- render-thread/OpenGL ownership.

The diagnostic wrapper delegates all authoritative behavior and changes no GL calls.

## Current GO / NO-GO

**NO-GO for a production atlas cache/read-ahead/decoder change until the exact-pack decomposition is collected.**

The existing evidence is sufficient to reject Stitcher-first work and to justify this profiler, but not to choose between resource I/O, ZIP inflate/staging and PNG decode. In particular, the `40.553 s` Decocraft task-sum is asynchronous worker time and is not itself recoverable menu wall time.

A production candidate may be opened in a second branch only if the new aggregate shows a materially positive ceiling and the blocks-atlas/ModelManager critical tail can plausibly realize it. The candidate must then be A/B tested on both the fast PC and the four-thread / 6 GiB laptop without this diagnostic instrumentation.

## Exact-pack result fields to fill after the diagnostic run

Record the single final `BOOTOPTIM_ATLAS_DECODE` aggregate and derive:

- all-sprite and Decocraft percentages for metadata, open, stream read, encoded staging/copy, PNG header, STB decode, animation/frame, constructor and residual;
- stream non-CPU share as the conservative I/O/wait indicator, explicitly not pure disk time;
- Decocraft exact dimension histogram/top pairs, encoded PNG bytes, decoded RGBA bytes and decoded:encoded ratio;
- repeated resource-key count/task ceiling;
- stream implementation classes used by Decocraft/mod JAR resources;
- ceiling translated first to task-sum and then separately to blocks-atlas/ModelManager critical wall; never add overlapping atlas futures.

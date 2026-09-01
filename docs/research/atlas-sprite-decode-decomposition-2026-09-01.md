# Atlas sprite decode decomposition — 2026-09-01

Status: **PROFILER READY / STATIC DECOCRAFT AUDIT COMPLETE / EXACT-PACK DECOMPOSITION PENDING**

This research lane follows PR #69's exact-pack finding that sprite loading, not geometric atlas packing, dominates the blocks-atlas preparation tail on the slow laptop. This document belongs to the diagnostic branch `agent/profile-atlas-decode-decomposition`; it is not a production optimization and must not be merged merely because CI is green.

## Integration baseline and preserved production behavior

The diagnostic branch was created from refreshed `agent/integration-current` SHA `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`.

The existing Decocraft 3.0.11 quarter-turn geometry reuse remains production behavior and is intentionally untouched. Its bake-lifetime cache, strict class/model guards, NeoForge quad metadata preservation and fail-open behavior remain authoritative. Atlas decode work must not be used as a reason to remove or weaken that optimization.

## Exact-pack evidence entering this campaign

PR #69 measured on the slow exact-pack laptop:

- all `SpriteResourceLoader.loadSprite`: `20,054` calls, `65,326.153 ms` task-sum, `3.258 ms` average, `422.158 ms` maximum;
- Decocraft: `5,771` sprites, `40,553.154 ms` task-sum, `7.027 ms` average, `395.541 ms` maximum;
- Decocraft therefore contributes **62.08%** of the measured sprite-load task-sum while representing **28.78%** of calls;
- the remaining `14,283` calls account for `24,772.999 ms`, or about `1.734 ms` each, so a Decocraft sprite is about **4.05x** as expensive as a non-Decocraft sprite on average in this run;
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

Resource-manager lookup/override selection happens before `loadSprite`: the method receives the already-selected `Resource`. The profiler therefore reports lookup as **outside this method** and measures only the `Resource.open()` boundary inside it. Resource-pack override order is not changed.

There is **no mipmap generation in `SpriteResourceLoader.loadSprite`**. Mipmap work occurs later in atlas preparation/upload paths and must not be charged to this sprite-load decomposition.

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

STB's single native decode call contains PNG chunk parsing, PNG DEFLATE, filter reconstruction/color conversion and allocation of the decoded native image. Those operations cannot be separated reliably from Java without native STB instrumentation. The profiler therefore reports that unit as `stbi_decode`; decoded output allocation is explicitly part of that number rather than being mislabelled as a separate Java allocation/copy.

## Diagnostic implementation

The branch adds three narrow mixins plus `AtlasDecodeProfiler`:

- wraps the authoritative `SpriteResourceLoader` passed to `SpriteLoader.runSpriteSuppliers` and delegates it unchanged;
- wraps the supplied NeoForge `SpriteContentsConstructor` only to time the call, preserving null/skip/custom subclass semantics;
- observes `Resource.metadata()` and `Resource.open()` only while a sprite load is active;
- wraps only the returned sprite `InputStream` and records delegated reads/bytes locally in the worker context;
- decomposes `NativeImage.read` at `TextureUtil.readResource`, PNG-header and STB boundaries;
- measures current-thread CPU inside resource-stream reads as a low-overhead indicator of ZIP/JAR work;
- emits no per-sprite log lines and keeps no global top-N lock;
- emits one final aggregate at first title-screen opening.

A startup-reload key set records whether the same `(sourcePackId, ResourceLocation)` is requested again. Its repeat task-sum is an **optimistic encoded-byte-cache ceiling**, not a proposal to cache `SpriteContents`: constructor identity/custom semantics are deliberately not assumed shareable.

The profiler also records the original stream implementation class and exact decoded dimensions. For Decocraft it emits the most common dimension pairs, total encoded PNG bytes actually read, total decoded RGBA bytes and their ratio.

### Control validation

GitHub `Build #937` and `Startup Benchmark #271` passed after correcting the encoded-byte counter. The control client reached title on NeoForge `21.1.248`; `2,567` sprite loads were observed and the aggregate's `encoded_bytes=834,225` exactly matched the stream-class aggregate's `bytes=834,225`. This specifically guards against mistaking `TextureUtil` staging buffer capacity for file length.

This control run validates Mixin signatures and accounting relationships, not the exact-pack percentages. It is deliberately not used to infer the laptop's bottleneck.

## Physical I/O versus ZIP/JAR decompression caveat

At the Java `InputStream` boundary, one delegated `read` can include filesystem wait, UnionFS/ZIP machinery, DEFLATE CPU and scheduler descheduling. The profiler measures both wall and current-thread CPU time for those reads:

- `stream_read_ms`: complete resource-stream read wall time;
- `stream_cpu_ms`: CPU consumed on that worker while the read call executes;
- `stream_non_cpu_ms = max(wall - CPU, 0)`.

`stream_non_cpu_ms` is **not pure physical-disk time** on a four-thread machine: descheduling/contention is included. It is nevertheless the closest low-overhead in-process ceiling for non-CPU resource waiting and can be cross-checked with the JFR `FileRead`/`FileChannelImpl.implRead` evidence. `stream_cpu_ms` includes JAR/ZIP inflate plus stream machinery; encoded staging/copy outside the delegated read is reported separately.

A production decision must not claim an exact physical-disk percentage unless the exact-pack run is paired with lower-level JFR/OS I/O evidence. The final report should therefore state both the conservative `resource stream (I/O + ZIP)` share and the `non-CPU wait` share.

## Exact Decocraft 3.0.11 resource audit

The exact published file audited was `decocraft-3.0.11-1.21.1-neoforge.jar`, SHA-256:

`b0589eb7d03b13bbf3b9c45df7f50db556a721882e9ad2bb6be0ff23e5a64526`

The audit was performed offline from Minecraft on the unmodified published JAR. It establishes:

- JAR size: `93,170,262` bytes (`88.854 MiB`), `21,330` entries;
- Decocraft PNG entries: `5,775`;
- `assets/decocraft/textures/*.png`: **5,773**;
- texture PNGs with `.png.mcmeta`: `122` (**2.11%**);
- encoded PNG bytes after the ZIP layer: `20,700,997` (`19.742 MiB`);
- bytes occupied by those entries after ZIP DEFLATE: `20,537,997` (`19.587 MiB`);
- **all 5,773 texture PNGs are ZIP-DEFLATED**;
- ZIP compressed/encoded ratio: `0.992126`, meaning the outer ZIP DEFLATE saves only `163,000` bytes, or **0.787%**, over already-compressed PNG data.

This is important: every Decocraft texture pays a per-entry ZIP DEFLATE layer even though that outer compression removes less than 1% of the PNG corpus size. Whether that CPU is material on the laptop is still a measurement question, but the packaging creates a plausible avoidable CPU tax with almost no storage benefit.

### Dimensions and decoded footprint

Across the 5,773 texture PNGs:

- encoded size median `2,905 B`, p90 `6,285 B`, p99 `19,707 B`, max `76,876 B`;
- pixel area median `4,096 px`, p90 `8,192 px`, p99 `32,768 px`, max `98,304 px`;
- **3,580 files (62.01%) are 64x64**;
- next common sizes are 64x32 (`495`), 32x32 (`403`), 128x128 (`365`), 64x128 (`212`) and 128x64 (`186`);
- total decoded pixel count is `31,755,072`, or `127,020,288` bytes / `121.136 MiB` at four bytes per pixel;
- decoded-RGBA / encoded-PNG ratio for the full corpus is about **6.136x**.

The corpus is therefore not dominated by a few giant PNGs. It is dominated by **thousands of modest 64x64-class images**, exactly the shape where per-entry open/ZIP/setup overhead can matter as much as raw byte throughput. STB decode still has a large total pixel workload, so this does not pre-judge open/read versus PNG decode.

### Organization and locality inside the JAR

Texture categories are:

- `item`: `3,255` files / `12,257,819` encoded bytes;
- `block`: `2,516` files / `8,429,094` encoded bytes;
- `gui`: `2` files / `14,084` encoded bytes.

The texture entries occupy a `20.066 MiB` physical span in the ZIP with **97.61% compressed-byte density**. In physical order the inter-entry gap is only `83 B` median, `93 B` p90 and `272 B` p99. Consecutive lexical texture paths have a `3,085 B` median physical-offset gap, `22,001 B` p99, with only `2 / 5,772` gaps above 1 MiB.

Therefore **poor on-disk JAR layout is not supported by the static evidence**. The textures are densely clustered and path order has good physical locality. Actual runtime request order can still be hostile, and per-entry ZIP/file-system setup can still be expensive; the exact-pack stream timings are required to distinguish those cases. A read-ahead design should not be justified merely by claiming the JAR is fragmented.

### Repetition / duplicate content

The JAR contains `80` exact-content duplicate groups, representing `108` files beyond the first copy and `662,583` encoded bytes beyond the first copies. That is only **1.87% of files** and **3.20% of encoded texture bytes**.

A content-deduplicated decoded cache therefore has a small static ceiling from exact duplicate PNG bytes alone. More importantly, resource IDs and metadata can differ even when PNG bytes match, so content deduplication must not bypass metadata or `SpriteContentsConstructor` semantics.

### Why Decocraft is ~62% of sprite task-sum

The evidence now supports a stronger, but still partially unresolved, explanation:

1. **Near-whole-corpus demand.** The exact-pack has `5,771` Decocraft sprite loads, only two fewer than the JAR's `5,773` texture PNG entries. That numerical match is strongly consistent with nearly the entire Decocraft texture corpus participating in startup rather than a small hot subset. The runtime key aggregate remains authoritative for confirming exact one-to-one/repeat behavior.
2. **Per-sprite cost is independently high.** Decocraft is only `28.78%` of calls but `62.08%` of task-sum; `7.027 ms` per Decocraft load is about **4.05x** the non-Decocraft average (`1.734 ms`). Sheer count alone cannot explain the 62% share.
3. **Images are larger than classic 16x16 Minecraft texture assumptions.** The median Decocraft image is 64x64 (`4,096 px`) and 62% are exactly 64x64, creating a material STB/pixel-allocation workload across thousands of files.
4. **Every PNG is double-compressed.** The outer ZIP DEFLATE saves only 0.787% but must still be traversed before STB decodes the PNG's own compressed stream. This is a credible source of resource-stream CPU, not yet a proven dominant source.
5. **Static locality is good.** The JAR is densely organized, so a claim that Decocraft is slow because its PNGs are scattered around the JAR is currently unsupported.
6. **Animation metadata is sparse.** Only 122 texture PNGs have `.mcmeta`, so animation metadata cannot explain the count-wide phenomenon by frequency alone; the exact timing aggregate will quantify its task share.

The missing discriminator is still the exact-pack phase split: how much of Decocraft's `40.553 s` task-sum is resource open/read/ZIP versus staging versus STB decode versus hooks/frame processing.

## Ceiling questions to compute from the exact-pack aggregate

### Bytes already available

Optimistic task ceiling = resource open + stream-read + encoded staging/copy removed while keeping metadata, STB decode, animation and constructor authoritative. This is the maximum benefit a perfect reload-scoped encoded-byte provider could expose before overlap/critical-path correction.

Because the static Decocraft corpus is only `19.742 MiB` encoded, a reload-scoped byte materialization is memory-plausible on a 6 GiB heap in isolation, but its GO decision depends on the measured wall saved and allocation/GC consequences, not just capacity.

### Pure PNG decode

Optimistic task ceiling = PNG header + STB decode + decode-wrapper work removed while keeping resource access and constructor behavior unchanged. This identifies whether a different decoder or decoded-image cache could matter at all.

A full Decocraft decoded RGBA corpus is about `121.136 MiB` before object/native allocator overhead and mip levels, so a reload-scoped decoded cache has a materially larger memory surface than an encoded-byte cache. Persistent decoded caching is more expensive still and requires exact invalidation.

### Reload-scoped encoded cache

Use `repeats` and `repeat_task_ms` only as an upper bound for same-key reuse. A valid implementation would cache immutable encoded bytes by the already-resolved resource identity/source for the duration of one reload, not by namespace alone. It must not bypass resource-pack override selection or reuse a `SpriteContents` across constructor identities.

If the exact-pack shows almost no repeated keys, a conventional same-resource memoization cache is a NO-GO even if raw stream time is high; the relevant mechanism would instead be eager/read-ahead materialization or lower-overhead JAR access.

### Read-ahead / batching by JAR

A GO requires the Decocraft aggregate to show meaningful open/stream/non-CPU time and low enough decode dominance that storage scheduling can affect the worker tail. The static audit says the JAR itself is densely laid out, so batching would target **per-entry open/ZIP overhead and runtime access ordering**, not file fragmentation.

Any implementation must preserve the selected `Resource` and its pack precedence; batching is about when bytes are read, never about choosing a different resource.

### Persistent decoded cache

Research only. A safe design would need a fingerprint covering at minimum:

- exact selected resource bytes/content hash;
- resource-pack stack/order and selected source identity;
- Minecraft/NeoForge and BootOptim cache schema;
- metadata bytes/sections that affect animation/frame interpretation;
- requested `SpriteContentsConstructor` semantics or a restriction to the vanilla/default constructor;
- decoder/output format assumptions.

A cache hit must still preserve custom loaders, constructor/null semantics, animation metadata and resource reload invalidation. GPU/OpenGL texture objects are never persisted or created on background threads.

## Compatibility / invalidation surface

Must preserve:

- all custom atlas/sprite source loaders;
- NeoForge `SpriteContentsConstructor`, including custom `SpriteContents` and null-to-skip behavior;
- metadata parsing/error behavior;
- resource-pack override order and reload invalidation;
- animation metadata/frame sizing;
- image close/free ownership on failures;
- later mip generation semantics;
- render-thread/OpenGL ownership.

A production encoded-byte cache would need at least reload lifetime + already-resolved pack/source identity in its key. A persistent cache additionally needs exact content/metadata fingerprints and version/schema invalidation. A decoded cache cannot assume that identical PNG bytes imply identical final `SpriteContents` because metadata and constructor behavior are separate semantic inputs.

The diagnostic wrapper delegates all authoritative behavior and changes no GL calls.

## Current GO / NO-GO

**NO-GO for a production atlas cache/read-ahead/decoder change until the exact-pack decomposition is collected.**

The current evidence is sufficient to:

- reject Stitcher-first work;
- reject a claim that Decocraft's JAR is physically fragmented;
- identify an almost whole-corpus Decocraft texture demand pattern;
- identify a suspicious all-DEFLATED outer ZIP layer with only 0.787% byte savings;
- show that exact-content deduplication alone has only a small static ceiling;
- justify measuring resource-stream CPU/non-CPU separately from STB PNG decode.

It is **not** sufficient to choose a production mechanism. In particular, the `40.553 s` Decocraft task-sum is asynchronous worker time and is not itself recoverable menu wall time. The real ceiling must be translated to the blocks-atlas / ModelManager critical tail without adding overlapping atlas futures.

A production candidate may be opened in a second branch only if the exact-pack aggregate shows a materially positive ceiling and the critical tail can plausibly realize it. The candidate must then be A/B tested on both the fast PC and the four-thread / 6 GiB laptop without this diagnostic instrumentation.

## Exact-pack result fields still required

Record the single final `BOOTOPTIM_ATLAS_DECODE` aggregate and derive:

- all-sprite and Decocraft percentages for metadata, open, stream read, encoded staging/copy, PNG header, STB decode, animation/frame, constructor and residual;
- stream CPU share as the conservative ZIP/JAR-work indicator;
- stream non-CPU share as the conservative I/O/wait indicator, explicitly not pure disk time;
- Decocraft runtime encoded bytes and dimension counts cross-checked against the static `5,773`-file / `20,700,997`-byte audit;
- repeated resource-key count/task ceiling;
- stream implementation classes used by Decocraft/mod JAR resources;
- ceiling translated first to task-sum and then separately to blocks-atlas/ModelManager critical wall; never add overlapping atlas futures.

Only after those fields exist should this document change from `NO-GO` to a specific production GO/NO-GO.

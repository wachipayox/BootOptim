# Model resource open → physical bytes — source graph and diagnostic gate (2026-09-01)

Status: **SOURCE-LEVEL ATTRIBUTION COMPLETE / LOW-OVERHEAD DIAGNOSTIC REQUIRED / PRODUCTION NO-GO**

Branch: `agent/profile-resource-open-physical-path`
Base: `agent/integration-current` at `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`

This report follows the current integration tree, PR #68/#69 evidence, NeoForge 1.21.1 source, Minecraft 1.21.1 mapped APIs, SecureJarHandler/UnionFS source, and JDK ZipFS mechanics. PR #69 remains diagnostic-only and must not be merged.

## Executive decision

**GO:** a stripped physical-path profiler that separates lazy supplier open, UnionFS channel/directory lookup, actual stream reads, and parse-inclusive time, aggregated by pack/stage with `LongAdder` and one dump after the main-menu marker.

**NO-GO:** any generic resource cache, model cache, extra worker pool, or production path/index patch before the diagnostic numbers below exist.

The strongest new source-level conclusion is that PR #69's `Resource.openAsReader = 7,961.276 ms task-sum` for block states is **not a measurement of JSON byte reading or UTF-8 decoding**. `openAsReader()` returns a `BufferedReader`; the content is consumed later by `GsonHelper.parse(reader)`. Therefore the 7.96 s is dominated by work performed while obtaining/opening the underlying `InputStream` plus construction of reader wrappers. On JAR-backed mod resources that means the lazy `IoSupplier` and PathPackResources/SecureJar/UnionFS/ZipFS open path can be expensive before the first character is parsed.

For block models, the ~57.35 s task-sum outside `BlockModel.fromStream` remains **unclassified**. It must not be called I/O until the diagnostic branch separates open from subsequent reads.

## Evidence carried forward

Slow laptop, exact pack, PR #69:

| Measurement | Count | Time |
| --- | ---: | ---: |
| block-state stack tasks | 11,435 | 8,649.468 ms task-sum |
| `Resource.openAsReader` | 11,435 resources | 7,961.276 ms task-sum |
| block-state Gson parse | same pipeline | 549.682 ms task-sum |
| block-state enumeration | — | 421.992 ms wall |
| block-model resource tasks | 44,103 | 63,243.150 ms task-sum |
| `BlockModel.fromStream` | same pipeline | 5,896.289 ms task-sum |
| block-model enumeration | — | 2,753.944 ms wall |

PR #68 whole-phase wall on the same laptop campaign build:

- block states: `15,420.822 ms`
- block models: `35,962.690 ms`
- all resource preparations: `75,140.983 ms`
- ModelManager preparation gate: `75,126.080 ms`
- critical order wait: `54,931.036 ms`
- critical post-turn: `22,678.691 ms`

Historical JFR from that laptop:

- `8,899` `FileRead` events;
- Decocraft JAR: `3,603` reads / about `11.8 MB`;
- several `FileChannelImpl.implRead` contention groups, including maxima in the hundreds of ms;
- ResourceReload workers carry a large share of allocation and contention.

These values are complementary, not additive. `task_sum` can exceed wall because resource tasks overlap; whole-phase wall can overlap other reload futures.

## Exact call graph: ModelManager to physical bytes

### 1. ModelManager schedules resource work

Minecraft 1.21.1 `ModelManager.reload(...)` starts both futures before combining them:

```text
ModelManager.reload
  ├─ loadBlockModels(resourceManager, executor)
  │    ├─ ResourceManager.listResources("models", json filter)
  │    ├─ one async task per Map.Entry<ResourceLocation, Resource>
  │    └─ Resource.openAsReader() → BlockModel.fromStream(reader)
  │
  └─ loadBlockStates(resourceManager, executor)
       ├─ ResourceManager.listResourceStacks("blockstates", json filter)
       ├─ one async task per ResourceLocation stack
       └─ for each Resource layer:
            Resource.openAsReader() → GsonHelper.parse(reader)
```

NeoForge's 1.21.1 patch initializes geometry loaders before these futures and adds model-bake hooks later; it does not replace the vanilla resource-loading structure.

### 2. ResourceManager performs pack-priority merging

```text
MultiPackResourceManager
  └─ per namespace: FallbackResourceManager
       ├─ ordered PackEntry list + pack filters
       ├─ listResources(...)
       │    └─ collapse matching layers to the resource that wins pack priority
       └─ listResourceStacks(...)
            └─ preserve the matching layered stack in resource-pack semantics
```

The important split is that **enumeration/merging builds `Resource` objects holding lazy `IoSupplier<InputStream>` values; it does not need to consume each JSON body at this point**.

Resource-pack priority therefore costs work in two places:

1. enumeration and merging/filter checks across packs;
2. later opening of the winning supplier (models) or every supplier in a retained stack (block states).

This explains why block states can open more resources than their final logical resource count: stack semantics intentionally preserve override layers.

### 3. NeoForge mod JARs use PathPackResources, not FilePackResources

NeoForge 1.21.1 `ResourcePackLoader.createPackForMod(...)` returns:

```java
new PathPackResources.PathResourcesSupplier(
    modFile.getSecureJar().getRootPath()
)
```

Therefore the normal path for Decocraft/Create/etc. is:

```text
mod JAR
  → SecureJar root Path
  → PathPackResources
  → UnionFileSystem / embedded ZipFileSystem
```

`FilePackResources` is still relevant for standalone ZIP resource packs, but it is not the primary path for ordinary NeoForge mod JAR assets in this pack.

### 4. PathPackResources creates a lazy IoSupplier

Mapped Minecraft 1.21.1 exposes this shape:

```text
PathPackResources.getResource(type, id)
  → resolve pack-type / namespace / resource path
  → PathPackResources.getResource(id, path)
  → returnFileIfExists(resolvedPath)
  → IoSupplier<InputStream>
```

Enumeration uses `listResources(...)` / `listPath(...)` and emits resource suppliers through `PackResources.ResourceOutput`.

Two costs are therefore distinct:

- **path/metadata discovery:** directory traversal, path construction, existence/attribute checks, merging;
- **supplier execution:** the later `IoSupplier.get()` that actually obtains an `InputStream`.

### 5. Resource.openAsReader is an open boundary, not a read-all boundary

`Resource` stores:

```text
PackResources source
IoSupplier<InputStream> streamSupplier
IoSupplier<ResourceMetadata> metadataSupplier
```

Its API is:

```text
Resource.open()
  → streamSupplier.get()

Resource.openAsReader()
  → open()
  → new InputStreamReader(input, UTF_8)
  → new BufferedReader(reader)
```

The reader constructors do not consume the JSON body. UTF-8 conversion and underlying byte reads happen when `BlockModel.fromStream` / Gson subsequently calls `Reader.read(...)`.

Consequences for PR #69:

- `block_states.open_reader = 7,961.276 ms task-sum` is evidence for **supplier/open-path cost**, not evidence that 7.96 s was spent parsing or decoding bytes;
- `block_states.json_parse_inclusive = 549.682 ms` contains the later reader consumption, including byte reads/decompression/UTF-8 decode plus Gson work;
- the block-model parser timer similarly includes later stream consumption, because its resource-open operation was not separately timed in PR #69.

### 6. SecureJar UnionFS resolves the path again when a stream/channel is opened

NeoForge 21.1.248 resolves `cpw.mods:securejarhandler:3.0.8`. The UnionFS implementation keeps a list of base paths and opens non-directory bases as embedded JDK ZIP filesystems. Its relevant flow is:

```text
Files.newInputStream(UnionPath)
  → UnionFileSystemProvider
  → UnionFileSystem.newReadByteChannel(UnionPath)
       → findFirstFiltered(path)
            for candidate base paths:
              toRealPath(base, unionPath)
              testFilter(...)
                → possibly read file attributes
              fastPathExists(realPath)
                → default FS: File.exists()
                → ZipFS/other FS: Files.exists(...)
       → Files.newByteChannel(realPath, READ)
            → for a JAR base, realPath belongs to embedded ZipFileSystem
```

For directory enumeration:

```text
UnionFileSystem.newDirStream(UnionPath)
  for every base path:
    toRealPath(...)
    fastPathExists(directory)
    Files.newDirectoryStream(directory)
    filter each entry
  Stream.concat(...).distinct()
```

This is a concrete repeated-lookup surface. For non-default filesystems, `testFilter` may obtain attributes and `fastPathExists` may then perform another provider lookup for the same candidate. Enumeration also validates directories before opening directory streams. Whether this repeated metadata work is *large enough* for a reload-scoped index is exactly what must be measured before implementation.

### 7. ZIP entry lookup, FileChannel and inflate

Once the resolved real path belongs to JDK ZipFS:

```text
ZipFileSystem.newInputStream(entryPath)
  → getEntry(path)                         [central-directory/index lookup]
  → getInputStream(entry)
       → EntryInputStream(entry, shared zip channel)
       → if method == DEFLATED:
            InflaterInputStream(EntryInputStream, Inflater)
       → if method == STORED:
            EntryInputStream directly
```

The `EntryInputStream` reads compressed/stored entry bytes through the ZIP filesystem's archive `SeekableByteChannel`; on the normal disk-backed case this reaches the JDK file channel and ultimately `FileChannelImpl` reads. SecureJar's UnionFS opens the embedded ZIP filesystem once and retains its channel; **a model resource does not normally reopen the physical JAR file handle from scratch for every JSON**. Per-resource open cost is instead path/filter/existence/ZipFS-entry/channel/stream setup on top of the already-open archive.

For DEFLATED entries, the physical compressed read and `Inflater` CPU are interleaved during later `InputStream.read(...)`. For STORED entries there is no inflate step.

Historical `FileChannelImpl.implRead` contention proves physical storage/channel waiting is present in the campaign, but it does not prove that all residual block-model task-sum is FileChannel time.

### 8. Character conversion and parsing

```text
ZipFS / file InputStream.read(byte[])
  → InputStreamReader UTF-8 decoder
  → BufferedReader char buffer
  → Gson JsonReader / BlockModel.fromStream
  → JSON object/model construction
```

The UTF-8 decoder is lazy and driven by reader consumption. `BufferedReader` construction contributes allocations but not body decoding.

## Stage attribution: what is known now

| Stage | Source operation | Evidence now | Cost status |
| --- | --- | --- | --- |
| pack merge / priority | MultiPack + FallbackResourceManager | source + enumeration timers | states `421.992 ms` wall; models `2,753.944 ms` wall include more than merge alone |
| directory/path lookup | PathPackResources + UnionFS `newDirStream` / exists / attrs | source; JFR compiler hotspot `UnionFS.newDirStream` | not yet isolated in runtime wall/task-sum |
| lazy supplier / stream open | `Resource.open` → `IoSupplier.get` | source; PR #69 `openAsReader` upper envelope | block states strongly dominant; exact substage missing |
| UnionFS path re-resolution/channel open | `findFirstFiltered` → `Files.newByteChannel` | source | not yet isolated |
| ZIP entry lookup | ZipFS `getEntry` / entry stream creation | source | nested in open/channel stage; not yet isolated |
| physical compressed/stored reads | ZipFS entry stream → archive channel → FileChannel | JFR + source | known real, not yet attributable specifically to model JSON |
| inflate | `InflaterInputStream` for DEFLATED entries | JDK source | not separately timed |
| UTF-8 chars | `InputStreamReader` | source + allocation evidence | lazy; included in parse-inclusive scope after open |
| Gson/model parse | Gson / `BlockModel.fromStream` | PR #69 | states `549.682 ms`; models `5,896.289 ms` task-sum |

## Top packs already implicated

PR #69 block-model task attribution, still **inclusive** and not equivalent to physical I/O:

| Namespace / pack family | Calls | Inclusive task-sum |
| --- | ---: | ---: |
| Decocraft | 7,280 | 22,978.790 ms |
| Create Food | ~5,286 | 11,281.600 ms |
| Create | 2,684 | 5,593.875 ms |
| TFMG | 2,196 | 4,904.709 ms |
| Design n' Decor | 3,123 | 3,615.457 ms |
| Minecraft | 9,301 | 3,553.251 ms |
| Builders Delight | 2,746 | 3,057.146 ms |

Decocraft is additionally independently supported by JFR: 3,603 physical reads / ~11.8 MB from its JAR. It is therefore the first pack to inspect in the new open/read output, but no Decocraft-specific production cache is justified yet.

## Critical-path ceiling before a production candidate

### Block states

`openAsReader` task-sum is `7.961 s`. Even an impossible zero-cost replacement cannot directly recover more than that measured task-sum from those tasks, and critical wall savings must also be no more than the `15.421 s` block-state future wall. Because the tasks overlap, **7.961 s is not a 7.961 s wall-time prediction**.

Useful diagnostic ceiling:

```text
0 <= block-state direct critical saving <= 7.961 s
```

The actual value is expected to be lower and must be derived from the new stage timings plus the ModelManager critical gate.

### Block models

PR #69 leaves `63.243 - 5.896 = 57.347 s` of task-sum outside `BlockModel.fromStream`, but the baseline laptop block-model future is only `35.963 s` wall. Therefore:

```text
unclassified model task-sum ceiling = 57.347 s        [not wall]
whole block-model future wall ceiling = 35.963 s      [absolute direct upper bound]
```

No resource-open optimization can directly save more wall time than the whole future it removes.

### Combined ModelManager resource front

Block models and block states are launched concurrently. Their direct phase ceilings therefore **must not be added**. A conservative direct wall upper bound for eliminating both entire futures is approximately the larger measured future, ~`35.963 s`, before considering indirect contention effects. The actual open/read-only ceiling is below that because parsing and non-resource work remain.

The wider ModelManager preparation gate is `75.126 s`; that remains the absolute gate ceiling for any collection of ModelManager-preparation optimizations.

## Diagnostic implementation on this branch

The branch intentionally does not copy PR #69's per-resource ranking machinery.

It adds only aggregate stages:

- `enumeration.wall`
- `pack.list_resources` by pack/source type
- `resource.open_as_reader`
- `resource.open_supplier` (`IoSupplier.get` / stream-open boundary)
- `resource.read_bytes` (actual underlying InputStream read-call elapsed time + logical bytes; accumulated locally and published once on close)
- `unionfs.open_channel`
- `unionfs.open_directory_stream`
- `block_models.parse_inclusive`
- `block_states.parse_inclusive`

Implementation constraints:

- no synchronized `PriorityQueue`;
- no per-resource log line;
- no resource-ID cardinality in aggregate keys;
- `LongAdder` only for final pack/stage buckets;
- byte-read time/bytes/calls accumulate in plain fields inside each stream wrapper, then merge once at close;
- one sorted dump only after the semantic `main_menu` marker, so report formatting cannot inflate time-to-menu;
- `task_sum` is labeled as such and is never reported as critical path.

`resource.read_bytes` intentionally measures the time spent inside the underlying stream's `read` calls. For JAR-backed DEFLATED entries it can contain both compressed FileChannel work and inflate. It is **not** claimed to separate those two subcomponents. Existing JFR `FileRead`/contention remains the independent physical-read evidence; a laptop rerun is justified only if CI proves the hooks are live and the source/CI output cannot decide the open-vs-read split.

## Candidate gates after diagnostic data

### Candidate A — reload-scoped path/entry index

**GO only if:** `pack.list_resources` / `unionfs.open_directory_stream` / `unionfs.open_channel` consumes a meaningful fraction of block-model/state critical time, with repeated lookups concentrated in PathPackResources/UnionFS packs.

Required invalidation:

- discard the entire index at every resource-pack reload;
- rebuild when the pack stack/order/filter set changes;
- never reuse an entry after the underlying physical pack changes;
- include physical file identity/version information for any persistence beyond a single reload (path, size, mtime at minimum; stronger content identity if needed).

Compatibility risk: resource filters, overlays, hidden/child packs, virtual/union paths, external resource packs, mods supplying nonstandard PackResources.

Current decision: **NO-GO production pending profiler.**

### Candidate B — reload-scoped materialized JSON bytes

This gives a clean ceiling if bytes can be substituted without changing resource semantics, but memory cost is proportional to the JSON corpus and it can merely move reads earlier if each resource is consumed once.

Required invalidation is identical to the exact resource reload/physical pack identity. Metadata and pack-source identity must be preserved. Do not retain stale byte arrays across reloads.

Current decision: **NO-GO production**. Use only as a deliberately isolated diagnostic ceiling if the open path proves dominant and source-level indexing cannot explain it.

### Candidate C — extra threads

Current decision: **NO-GO**. The laptop already has four logical CPUs, active compiler threads, ResourceReload workers, GC pressure and FileChannel contention. More concurrency is not a default solution.

### Candidate D — generic ModelManager/resource cache

Current decision: **NO-GO**. Prior BootOptim experiments already rejected low-hit/high-retention caches and several count-only memoization ideas. This front needs a measured physical-path mechanism, not another generic cache.

## FilePackResources compatibility branch

Standalone ZIP resource packs take a different implementation path:

```text
FilePackResources
  → SharedZipFileAccess / ZipFile
  → resource-path lookup / ZipEntry
  → IoSupplier<InputStream>
  → ZipFile.getInputStream(entry)
  → inflater/read path
```

They do not require SecureJar UnionFS. The diagnostic keys source class explicitly so a future optimization can avoid assuming all packs are PathPackResources.

## ModernFix caveat

The exact pack contains ModernFix 5.27.14. PR #69's reflective probe failed to establish effective values for `mixin.perf.resourcepacks` and `mixin.perf.dynamic_resources`. ModernFix has a `perf.resourcepacks` PathPackResources mixin in its source tree, so any production PathPackResources index must first establish the effective runtime configuration and transformation order. Do not claim compatibility or duplicate its caching until that is resolved.

The present diagnostic is observational and uses `require = 0` hooks; Startup CI must prove the hooks still apply in the NeoForge dev environment. The exact-pack run, if eventually needed, must also report whether the relevant ModernFix mixin is active.

## GO / NO-GO

**GO diagnostic** when Build CI and Startup CI pass with non-empty `resource.open_supplier` and `resource.read_bytes` output and no BootOptim mixin application failures.

**NO-GO production** until the diagnostic can answer all of the following:

1. What fraction of `openAsReader` is `IoSupplier/open` versus subsequent `read`?
2. For PathPackResources, what fraction of open is visible at UnionFS channel/path lookup?
3. Which packs dominate open and read separately?
4. Does enumeration/directory lookup matter enough to justify an index?
5. Is the measured opportunity on the ModelManager critical path rather than only large task-sum?
6. Can a ceiling experiment eliminate the suspected layer without violating reload/pack semantics?

No slow-laptop rerun should be requested before CI demonstrates that these counters are populated and source/CI cannot supply the exact-pack attribution.

## Source references

- BootOptim integration guidance and history: `AGENTS.md`, `docs/research/README.md`, PR #68, PR #69.
- NeoForge 1.21.1 `ModelManager.java.patch`: `neoforged/NeoForge`, branch `1.21.1`.
- NeoForge 1.21.1 `ResourcePackLoader.java`: mod packs are `PathPackResources` rooted at `SecureJar.getRootPath()`.
- Minecraft 1.21.1 mapped APIs: `Resource`, `PathPackResources`, `FilePackResources`, `MultiPackResourceManager`, `FallbackResourceManager`.
- SecureJarHandler runtime selected by NeoForge 21.1.248: `cpw.mods:securejarhandler:3.0.8`.
- SecureJarHandler UnionFS source: `McModLauncher/securejarhandler`, `cpw.mods.niofs.union.UnionFileSystem`.
- JDK ZipFS: `jdk.nio.zipfs.ZipFileSystem` (`getEntry` → `EntryInputStream`; DEFLATED entries wrap with `InflaterInputStream`).

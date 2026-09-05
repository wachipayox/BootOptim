# Model resource open → physical bytes — source graph and diagnostic gate (2026-09-01)

Status: **SOURCE CALL GRAPH COMPLETE / CI-VALIDATED DIAGNOSTIC / PRODUCTION NO-GO**

Branch: `agent/profile-resource-open-physical-path`  
Base: `agent/integration-current` at `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`  
Draft PR: #71 — **DIAGNOSTIC ONLY / NO MERGE**

This report follows the current integration tree, PR #68/#69 evidence, NeoForge 1.21.1 / 21.1.248 source, mapped Minecraft 1.21.1 resource APIs, SecureJarHandler/UnionFS source, and JDK ZipFS mechanics. PR #69 remains diagnostic-only and must not be merged.

## Decision

**GO:** one low-overhead exact-pack diagnostic run of PR #71, because Build CI and Startup CI now prove that the new `open_supplier` and `read_bytes` counters are live, while CI cannot provide the exact modpack/slow-disk attribution.

**NO-GO:** any production cache, resource index, extra worker pool, or ModelManager redesign from the current evidence alone.

The key source-level correction to PR #69 is:

> `Resource.openAsReader()` is an **open/wrapper boundary**, not a read-all/parse boundary.

`openAsReader()` obtains the lazy `IoSupplier<InputStream>` and constructs `InputStreamReader` + `BufferedReader`. The JSON body is consumed later by `GsonHelper.parse(reader)` or `BlockModel.fromStream(reader)`. Therefore the block-state `7,961.276 ms` `openAsReader` task-sum is evidence that **opening the underlying resource is expensive**; it is not evidence that 7.96 s was spent decoding characters or parsing JSON.

For block models, the `63,243.150 - 5,896.289 = 57,346.861 ms` residual remains **unclassified task-sum**. It must not be labelled I/O.

## Evidence carried forward

### PR #69 exact laptop

| Measurement | Count | Cost |
| --- | ---: | ---: |
| block-state resource-stack tasks | 11,435 | 8,649.468 ms task-sum |
| `Resource.openAsReader` | 11,435 | 7,961.276 ms task-sum |
| block-state Gson parse | — | 549.682 ms task-sum |
| block-state enumeration | — | 421.992 ms wall |
| block-state scheduling | — | ~27.7 ms wall |
| block-state collect | — | ~10.8 ms wall |
| block-model resource tasks | 44,103 | 63,243.150 ms task-sum |
| `BlockModel.fromStream` | — | 5,896.289 ms task-sum |
| block-model enumeration | — | 2,753.944 ms wall |
| block-model scheduling | — | 229.007 ms wall |
| block-model collect | — | 49.607 ms wall |

`task_sum` is concurrent work and must not be subtracted from startup wall time.

### PR #68 baseline laptop wall

- block states: `15,420.822 ms`
- block models: `35,962.690 ms`
- all resource preparations: `75,140.983 ms`
- ModelManager preparation gate: `75,126.080 ms`
- critical order wait: `54,931.036 ms`
- critical post-turn: `22,678.691 ms`

### Historical JFR

- `8,899` FileRead events;
- Decocraft JAR: `3,603` reads / about `11.8 MB`;
- multiple `FileChannelImpl.implRead` contention groups, some with maxima in the hundreds of milliseconds;
- ResourceReload workers carry substantial allocation and contention.

This proves that real physical reads exist in the campaign. It does **not** prove that the block-model residual is all FileChannel or all I/O.

## Exact source-level call graph

### A. `ModelManager` launches both resource fronts concurrently

```text
ModelManager.reload(...)
  ├─ loadBlockModels(resourceManager, executor)
  │    ├─ ResourceManager.listResources("models", *.json)
  │    ├─ async task per Map.Entry<ResourceLocation, Resource>
  │    └─ Resource.openAsReader()
  │         └─ BlockModel.fromStream(reader)
  │
  └─ loadBlockStates(resourceManager, executor)
       ├─ ResourceManager.listResourceStacks("blockstates", *.json)
       ├─ async task per logical ResourceLocation stack
       └─ for every retained Resource layer
            Resource.openAsReader()
              └─ GsonHelper.parse(reader)
```

NeoForge's `ModelManager` patch initializes geometry loaders before these futures and adds later bake hooks; it does not replace this vanilla resource-loading shape.

Block models and block states therefore overlap. Their phase/task ceilings must not be added.

### B. `MultiPackResourceManager` / `FallbackResourceManager` perform priority and stack merging

```text
MultiPackResourceManager
  └─ FallbackResourceManager per namespace
       ├─ ordered PackEntry list
       ├─ pack filters / resource filters
       ├─ listResources(...)
       │    └─ resolve the resource that wins priority
       └─ listResourceStacks(...)
            └─ preserve all matching layers required by stack semantics
```

This stage is primarily **metadata/enumeration/merging**. It produces `Resource` objects containing lazy `IoSupplier<InputStream>` instances. The JSON body does not need to be consumed here.

The block-state path intentionally opens multiple layers when resource-pack stack semantics require them. Those are not automatically redundant reads.

### C. NeoForge mod JARs normally enter `PathPackResources`

NeoForge 1.21.1 `ResourcePackLoader.createPackForMod(...)` constructs:

```java
new PathPackResources.PathResourcesSupplier(
    modFile.getSecureJar().getRootPath()
)
```

So ordinary mod JAR resources such as Decocraft/Create normally follow:

```text
mod JAR
  → SecureJar.getRootPath()
  → PathPackResources
  → UnionPath / UnionFileSystem
  → embedded JDK ZipFileSystem
```

They do **not** normally use `FilePackResources`.

`FilePackResources` remains relevant for standalone ZIP resource packs and must be kept as a separate compatibility path.

### D. `PathPackResources` does path resolution first and returns a lazy supplier

Conceptually:

```text
PathPackResources.getResource(type, id)
  → resolve pack-type directory
  → resolve namespace
  → resolve resource path
  → validate/existence path
  → IoSupplier<InputStream>
```

Enumeration goes through `listResources(...)` / path walking and emits lazy suppliers via `PackResources.ResourceOutput`.

This creates two different potential costs:

1. **enumeration/path metadata** — directory walking, path construction, existence/attribute checks, pack merging;
2. **supplier execution** — later opening of the selected path when `Resource.open()` calls `IoSupplier.get()`.

### E. `Resource.openAsReader()`

`Resource` stores:

```text
PackResources source
IoSupplier<InputStream> streamSupplier
IoSupplier<ResourceMetadata> metadataSupplier
```

The relevant flow is:

```text
Resource.open()
  → streamSupplier.get()

Resource.openAsReader()
  → Resource.open()
  → new InputStreamReader(inputStream, UTF_8)
  → new BufferedReader(inputStreamReader)
  → return reader
```

At method return the JSON is not necessarily read. `InputStreamReader` performs UTF-8 conversion lazily when chars are requested; `BufferedReader` fills its char buffer lazily when consumed.

Hence:

- `openAsReader` = supplier/open + wrapper construction;
- subsequent parser scope = underlying byte reads + optional decompression + UTF-8 decode + parser/model construction.

### F. SecureJar / UnionFS path lookup

NeoForge 21.1.248 resolves `cpw.mods:securejarhandler:3.0.8`.

The UnionFS source shape is:

```text
UnionFileSystem.newReadByteChannel(UnionPath)
  → findFirstFiltered(path)
       for base path candidates:
         toRealPath(base, unionPath)
         testFilter(...)
           → may read BasicFileAttributes
         fastPathExists(realPath)
           → default FS: File.exists()
           → other FS: Files.exists(...)
  → Files.newByteChannel(realPath, READ)
```

Directory enumeration is roughly:

```text
UnionFileSystem.newDirStream(UnionPath)
  for each base path:
    toRealPath(...)
    fastPathExists(directory)
    Files.newDirectoryStream(directory)
    filter entries
  concatenate + distinct
```

This is the clearest source-level repeated-lookup surface. For some candidates, filter evaluation can read attributes and existence is then checked again. The same UnionPath also has to be resolved again when its lazy supplier is eventually opened after enumeration.

That repeated metadata work is a **candidate hypothesis**, not yet a production optimization.

### G. JDK ZipFS entry lookup and stream setup

Once UnionFS resolves to a path inside an embedded JAR ZipFileSystem:

```text
ZipFileSystem.newInputStream(entryPath)
  → getEntry(path)                       [ZIP central-directory/index lookup]
  → getInputStream(entry)
       → EntryInputStream(entry, shared archive channel)
       → if DEFLATED:
            InflaterInputStream(...)
       → if STORED:
            EntryInputStream directly
```

SecureJar opens the embedded ZIP filesystem and retains its archive channel. Therefore a model JSON does **not normally reopen the physical JAR file handle from scratch** for every resource. Per-resource open cost is more plausibly:

- UnionPath/path-filter/existence lookup;
- ZipFS entry lookup;
- entry channel/InputStream construction;
- inflater setup for compressed entries.

### H. Physical reads, inflater, chars, parse

When the parser consumes the reader:

```text
Gson / BlockModel parser
  → BufferedReader.read(...)
  → InputStreamReader / UTF-8 decoder
  → underlying InputStream.read(...)
      → if DEFLATED: InflaterInputStream
           → ZipFS EntryInputStream
      → if STORED: ZipFS EntryInputStream
           → archive SeekableByteChannel
           → disk-backed FileChannel / FileChannelImpl
```

For DEFLATED entries, FileChannel reads and inflater CPU are interleaved inside the later stream-read calls. For STORED entries there is no inflate step.

Therefore the diagnostic `resource.read_bytes` bucket is intentionally **read-inclusive**: it can contain archive read waiting plus inflater work. Separating FileChannel from inflater further requires lower-layer evidence such as JFR or a bootstrap/JDK-level instrument, not a normal client mixin.

## FilePackResources path

Standalone ZIP resource packs use a different stack:

```text
FilePackResources
  → SharedZipFileAccess / ZipFile
  → ZipEntry lookup
  → IoSupplier<InputStream>
  → ZipFile.getInputStream(entry)
  → read / inflater path
```

There is no SecureJar UnionFS requirement here. Any future production optimization must preserve both pack implementations and nonstandard `PackResources` supplied by mods.

## Stage classification

| Requested category | Source-level stage | Current attribution |
| --- | --- | --- |
| 1. metadata/path lookup | Fallback merge, PathPack list/walk, UnionFS resolution/exists/attrs, Zip entry lookup | enumeration wall + per-pack `listResources`; exact Union/Zip subshare still nested |
| 2. open handles/streams | `Resource.open` → `IoSupplier.get` → PathPack/Zip entry stream setup | `resource.open_supplier` |
| 3. read bytes | Zip entry stream → archive channel/FileChannel | included in `resource.read_bytes`; historical JFR independently proves physical reads |
| 4. decompress | `InflaterInputStream` for DEFLATED entries | included in `resource.read_bytes`, not independently split |
| 5. convert to chars | `InputStreamReader` UTF-8 decode + BufferedReader fill | later parser consumption, not reader construction |
| 6. parse | Gson / `BlockModel.fromStream` | parse-inclusive counters |
| 7. unnecessary repeat | repeated UnionFS path/filter/exists/entry resolution after enumeration; possible repeated physical reads from hot JARs | hypothesis requiring exact-pack attribution; resource-stack layers are not presumed redundant |

## Existing top-pack evidence

PR #69 block-model task attribution remains inclusive, not physical I/O:

| Pack family | Calls | Inclusive task-sum |
| --- | ---: | ---: |
| Decocraft | 7,280 | 22,978.790 ms |
| Create Food | ~5,286 | 11,281.600 ms |
| Create | 2,684 | 5,593.875 ms |
| TFMG | 2,196 | 4,904.709 ms |
| Design n' Decor | 3,123 | 3,615.457 ms |
| Minecraft | 9,301 | 3,553.251 ms |
| Builders Delight | 2,746 | 3,057.146 ms |

Decocraft is independently supported by JFR: `3,603` physical reads / ~`11.8 MB` from its JAR. It is the first pack to inspect in open-vs-read attribution, but this is **not** enough to justify a Decocraft-specific cache.

## Critical-path ceilings before production

### Block states

PR #69 measured `7.961 s` of `openAsReader` task-sum and `15.421 s` whole future wall.

An impossible zero-cost removal of every measured reader-open cannot save more direct work than the measured task-sum, and cannot save more wall than the whole future:

```text
0 <= direct critical saving from this open front <= 7.961 s
```

This is a ceiling, not a prediction.

### Block models

The unclassified residual is:

```text
63.243150 - 5.896289 = 57.346861 s task-sum
```

But whole block-model future wall is only `35.963 s`.

```text
unclassified task-sum ceiling: 57.347 s      [not wall]
whole block-model wall ceiling: 35.963 s     [absolute direct wall bound]
```

### Combined models + states

They are launched concurrently. Do not add their phase ceilings. A conservative bound for deleting both entire futures is approximately the larger future wall, ~`35.963 s`, before indirect contention effects.

The wider ModelManager preparation gate, `75.126 s`, is the absolute gate ceiling for any collection of preparation optimizations.

## Diagnostic branch design

PR #71 deliberately avoids PR #69's observer-cost problem.

Aggregates:

- `enumeration.wall`
- `pack.list_resources` by source pack/class, only inside ModelManager enumeration scopes
- `resource.open_as_reader`
- `resource.open_supplier`
- `resource.read_bytes` elapsed time + logical bytes
- `block_models.parse_inclusive`
- `block_states.parse_inclusive`

Properties:

- no synchronized `PriorityQueue`;
- no per-resource logging;
- no resource-ID key cardinality;
- `LongAdder` only for aggregate buckets;
- each measured InputStream accumulates read time/bytes in plain local fields and publishes once at close;
- one sorted dump after the semantic main-menu marker;
- output explicitly labels accumulated time as task-sum, not wall/critical path.

## CI validation

### Build CI

Build run #919: **PASS**.

### Startup CI

Startup run #263: **PASS**, main menu reached, no BootOptim mixin application failure, and both required open/read counters were populated.

The CI run is vanilla + NeoForge + BootOptim, **not the exact pack**, so these numbers are only hook/overhead sanity checks:

| CI stage | Vanilla block models | Vanilla block states |
| --- | ---: | ---: |
| parse-inclusive | 340.527 ms / 3,891 resources | 59.492 ms / 1,062 resources |
| `openAsReader` | 208.790 ms | 97.096 ms |
| `open_supplier` | 108.754 ms | 69.395 ms |
| measured stream reads | 1.214 ms / 999,728 bytes | 0.596 ms / 890,991 bytes |
| enumeration wall | 48.730 ms | 51.761 ms |

This proves that the profiler can distinguish open from later stream-read cost. It does **not** imply that the exact pack will have the same proportions.

### Direct UnionFS hook was rejected after CI

An initial optional `@Pseudo` mixin targeting `cpw.mods.niofs.union.UnionFileSystem` produced:

```text
Error loading class: cpw/mods/niofs/union/UnionFileSystem (ClassNotFoundException)
```

Although `securejarhandler-3.0.8.jar` is present in the runtime classpath, it lives in the MC-BOOTSTRAP/module layer and is not a valid target for this normal client mixin configuration at target discovery time.

That hook has been removed rather than leaving a dead profiler that falsely claims UnionFS attribution.

For the exact-pack run, `resource.open_supplier` is therefore the inclusive boundary covering PathPackResources → NIO provider → UnionFS/ZipFS entry open. Existing JFR supplies the independent physical-FileRead evidence. A deeper UnionFS/FileChannel split would require a different bootstrap/JFR instrumentation mechanism and is not justified until the inclusive open-vs-read result proves that it would change a production decision.

## How to interpret the next exact-pack result

### If `resource.open_supplier` dominates while `resource.read_bytes` is small

Most opportunity is before body consumption: repeated path/filter/existence lookup, ZIP entry lookup, or stream/inflater setup.

Then a reload-scoped **path/entry index** may deserve a diagnostic ceiling.

Do **not** start with a byte cache: materialized bytes would target the wrong stage.

### If `resource.read_bytes` dominates

Use JFR to separate physical FileChannel waiting from inflate/CPU and inspect which JARs dominate. A byte-materialization experiment may be a valid diagnostic ceiling, but still not automatically production.

### If enumeration/listing dominates

A reload-scoped pack/path index may be justified if repeated directory lookup is demonstrated and it is not duplicating ModernFix.

### If parse remains dominant

Physical-path caching is the wrong target; return to parser/model materialization work with exact source evidence.

## Production candidate gates

### A. Reload-scoped path/entry index

**Current: NO-GO.**

GO only if exact-pack data shows a meaningful critical-path open/enumeration share concentrated in PathPackResources/UnionFS/ZipFS lookup.

Mandatory invalidation:

- discard on every resource-pack reload;
- rebuild on pack order/filter/overlay changes;
- invalidate on physical pack replacement/change;
- never return a stale path/entry after a JAR/ZIP/directory change;
- any persistence beyond one reload requires physical identity/version validation, at minimum path + size + mtime and stronger identity where collisions are unsafe.

Compatibility risks:

- pack priority and filters;
- resource stacks;
- overlays/hidden child packs;
- UnionFS virtual paths;
- standalone FilePackResources;
- nonstandard mod PackResources;
- ModernFix resource-pack optimizations/transformation order.

### B. Reload-scoped materialized JSON bytes

**Current: NO-GO production.**

Only useful as a ceiling if stream-read cost dominates. Memory scales with corpus size, and if each JSON is consumed only once it may merely move the read earlier.

Invalidation must be exact at resource reload and physical pack change; pack identity and metadata semantics must be preserved.

### C. More threads

**NO-GO.**

The laptop already has four logical CPUs, active compiler work, ResourceReload contention, GC pressure, and FileChannel contention.

### D. Generic ModelManager/resource cache

**NO-GO.**

Prior BootOptim experiments already rejected multiple low-hit/high-retention caches and count-only memoization ideas. This front needs a measured path/read mechanism, not another generic cache.

## ModernFix caveat

The exact pack contains ModernFix 5.27.14. ModernFix source has a `perf.resourcepacks` PathPackResources optimization, while PR #69 could not establish the effective runtime values for `mixin.perf.resourcepacks` / `mixin.perf.dynamic_resources`.

Before any production PathPackResources index, establish the effective exact-pack ModernFix configuration and transformation order. Do not duplicate an active cache or claim compatibility without this check.

## Final GO / NO-GO

**GO:** keep PR #71 as a draft diagnostic and run it once on the exact pack/slow laptop now that Build + Startup CI prove the data path is live. This exact-pack result cannot be obtained from CI because CI does not contain the modpack/JAR corpus or the laptop storage/contention characteristics.

**NO-GO:** merge PR #71, merge PR #69, or implement a production cache/index before the exact-pack diagnostic answers:

1. how much of the reader-open cost is `IoSupplier/open` versus later stream reads;
2. which packs dominate open and read separately;
3. whether enumeration/listing is material;
4. whether the opportunity overlaps the ModelManager critical gate rather than being only large task-sum;
5. whether the result points to lookup/indexing, body materialization, or neither.

## Source references

- BootOptim: `AGENTS.md`, `README.md`, `docs/research/README.md`, PR #68, PR #69.
- NeoForge 1.21.1: `ModelManager.java.patch` and `ResourcePackLoader.java`.
- Runtime: NeoForge 21.1.248, `cpw.mods:securejarhandler:3.0.8`.
- Minecraft 1.21.1 mapped APIs: `Resource`, `PathPackResources`, `FilePackResources`, `MultiPackResourceManager`, `FallbackResourceManager`.
- SecureJarHandler source: `cpw.mods.niofs.union.UnionFileSystem` / `UnionFileSystemProvider`.
- JDK ZipFS: `jdk.nio.zipfs.ZipFileSystem` entry lookup, `EntryInputStream`, and `InflaterInputStream` path.

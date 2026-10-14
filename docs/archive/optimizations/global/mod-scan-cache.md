# Persistent mod scan cache

## Scope

Global NeoForge/FML optimization. Implemented by BootOptim's bootstrap `IModFileReader`, before the regular mod entrypoint exists.

Kill switch: `-Dboot_optim.scanCache=false`

## Bottleneck

NeoForge normally rebuilds `ModFileScanData` by scanning mod classes on every launch. Large packs can contain tens of thousands of scannable classes, so repeated warm launches pay the same ASM metadata work even when the mod JARs are unchanged.

## Mechanism

`CachingModFileReader` remains a drop-in reader for normal `neoforge.mods.toml`/manifest handling and only changes `ModFile.compileContent()`:

1. For regular files and when the cache is enabled, compute a cache key.
2. Try to deserialize the previously stored `SecureJar.Status` and `ModFileScanData`.
3. On a hit, attach the current `ModFileInfo` and return the reconstructed scan data.
4. On a miss, run the original `super.compileContent()` scanner and persist that authoritative result asynchronously.
5. Any cache read/decode/persistence failure falls back to stock scanning; cache corruption is deleted when possible.

The key currently includes the source file name, file size, last-modified timestamp, filesystem file key, FML loader implementation version, BootOptim version, Java feature version, and cache format version. It is SHA-256 hashed before use as a filename.

BootOptim also keeps `.bootoptim/cache-version.txt`; a BootOptim version change invalidates the persistent namespace. The BootOptim version is additionally part of each cache key, so stale entries cannot match even if cleanup fails.

## Storage

Warm scan entries are stored under:

`<game directory>/.bootoptim/mod-scan-cache-v1/`

Writes use a temporary file followed by an atomic replacement when the filesystem supports it.

## Safety invariants

- Non-regular sources always use stock scanning.
- Cache disabled => exact stock path.
- Cache miss => exact stock path.
- Cache read/decode exception => exact stock path.
- The cache stores the scanner's completed output; it does not invent annotations/classes.
- Security status is restored with the cached result.
- A valid null `ModFileScanData.ClassData.parent()` is encoded and decoded explicitly.

## Resource trade-offs

Warm starts exchange ASM/class metadata scanning for binary file reads and object reconstruction. Disk space grows with the number/size of scanned mod metadata entries and is invalidated with BootOptim versions. There is no intentional long-lived in-memory copy of the entire cache.

## Measured evidence

A CI synthetic benchmark with 8,500 classes measured approximately:

- stock scan: `112.958 ms`
- BootOptim cold scan + persistence scheduling: `123.222 ms`
- BootOptim warm cache: `8.728 ms`
- warm saved time in that benchmark: `92.27%` / `12.94x`

The synthetic result isolates scanner cost. Real pack startup savings are smaller than the percentage above because scanning is only one part of total startup and some work overlaps other startup tasks.

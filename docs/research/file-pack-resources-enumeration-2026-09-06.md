# FilePackResources ZIP enumeration — 2026-09-06

## Status

`PROFILED` diagnostic only. The branch adds no cache, index, ordering change, or new worker. Do not merge the profiler as production code.

## Hypothesis

ModernFix 5.27.14 selects `mixin.perf.resourcepacks`, but its `FilePackResourcesMixin` is permanently disabled at the pack's GA feature level because that mixin requires BETA. Vanilla `FilePackResources` therefore remains the active implementation for external ZIP resource packs.

In Minecraft 1.21.1, `getNamespaces` and every `listResources` call enumerate the complete `ZipFile.entries()` sequence and filter names in Java. The work may be material during the initial reload, but an inclusive method total is not itself a recoverable time-to-menu saving. The first safe step is to attribute that work by pack/type and compare it with the resource-reload critical path.

## Source evidence

- PR #137 exact-pack audit: `FilePackResourcesMixin` is not selected structurally under ModernFix `5.27.14+mc1.21.1`; `PathPackResourcesMixin`, faster texture stitching and wall-shape deduplication are already selected where applicable.
- Vanilla 1.21.1 `FilePackResources.getNamespaces(PackType)` scans `ZipFile.entries()` once and extracts namespaces.
- Vanilla 1.21.1 `FilePackResources.listResources(...)` scans `ZipFile.entries()` again for each namespace/path request.
- `getResource(...)` uses `ZipFile.getEntry(...)`, so the diagnostic deliberately does not assume that direct resource opens have the same cost as enumeration.
- PR #71/#136 established that the earlier global `Resource` profiler was semantically unsafe and that its task-sum must not be presented as a critical-path saving.
- PR #72 established a separate, promising but more invasive UnionFS/ZipFS eager-materialization hypothesis for mod JAR resources. This file-pack diagnostic does not mix those paths.

## Diagnostic mechanism

With `-Dboot_optim.profileFilePackResources=true`, a client-only Mixin measures the wall duration of vanilla `getNamespaces` and `listResources` calls, keyed by operation, pack type and `packId`. It reports once after the main-menu marker, so formatting is outside TTMM. The hook is fail-open and does not touch returned sets, resource ordering, `ResourceOutput`, ZIP entries or close/reload behavior.

The counters are intentionally bounded to method aggregates. They do not claim exclusive CPU or critical-path time, and `listResources` rows are not summed across overlapping resource-worker calls.

## Gate

1. Build and startup smoke must show zero BootOptim Mixin errors with the property disabled and enabled.
2. Hosted exact-pack smoke must show non-empty rows and valid resource selection/atlas/menu behavior.
3. Compare aggregate wall with PR #47/PR #138 preparation barriers and the actual reload-to-menu interval. If the totals are small or fully overlapped, close the direction.
4. Only a material exclusive tail can justify a separate candidate. Any index must preserve pack precedence, namespace/path ordering, invalidation on reload/close, and fail open when ModernFix starts applying its own ZIP index.

No physical laptop run is requested until the hosted diagnostic proves a material, attributable ceiling.

## Links

- [PR #137](https://github.com/wachipayox/BootOptim/pull/137) — ModernFix effective-option audit.
- [PR #71](https://github.com/wachipayox/BootOptim/pull/71) — unsafe first resource-open profiler.
- [PR #72](https://github.com/wachipayox/BootOptim/pull/72) — atlas/resource-open decomposition and distinct UnionFS hypothesis.
- [PR #138](https://github.com/wachipayox/BootOptim/pull/138) — resource-reload boundary profiler.

# FML 4.0.43 BackgroundScanHandler / addForScanning barrier attribution (2026-09-13)

## Scope

Agent 140, diagnostic-only. Base: `agent/integration-current` at `b3f0c5f6462a359483883741ac16d2f154868900`. Runtime source pin: NeoForged FancyModLoader `15c77cf658f360c171668a8700d02c30ad0cd965`, module `fml_loader@4.0.43`.

This investigation is deliberately limited to `BackgroundScanHandler`, `LoadingModList.addForScanning`, scan worker completion, and the later scan barrier. It does not attribute `validateLanguages`, the AT/mixin/enum publication methods, `ModSorter`, Connector, or JiJ.

## Source contract

`ModValidator.stage2Validation()` constructs `BackgroundScanHandler` only after the final `LoadingModList` exists, then calls `LoadingModList.addForScanning()`. `addForScanning()` registers the list with the handler and serially submits every `ModFile` to `BackgroundScanHandler.submitForScanning()`; it does **not** wait for scanning.

`BackgroundScanHandler` uses `maxThreads - 1` worker threads (minimum one), explicitly reserving one thread for Minecraft bootstrap. Each submission runs `ModFile.compileContent` asynchronously, publishes its result/error to that `ModFile`, and finally calls synchronized `addCompletedFile`. The blocking global barrier is later: `ModLoader.gatherAndInitializeMods()` calls `waitForScanToComplete()` before constructing mod containers. The barrier shuts down the executor and polls `awaitTermination(50 ms)` while running the supplied ticker; non-complete status throws.

This is already a prepare/barrier design: per-file scan preparation overlaps the bootstrap interval between `stage2Validation` and `gatherAndInitializeMods`, followed by one global failure/availability barrier. Publication of per-file results occurs in completion order internally, but stock consumers are behind the global barrier. Moving that barrier past mod-container construction would change failure and side-effect order and is not equivalent.

Starting scans earlier is also not source-equivalent. Before the current launch point, Stage 2 can still fail in earlier serial work; stock would then never start scan workers. Advancing submission across that work would introduce worker I/O/security scanning and scan diagnostics before a failure that currently prevents them, and would add new concurrency against shared jar/resource state. No exact failure-order-preserving earlier launch point was demonstrated.

## Diagnostic probe

Branch: `agent140/background-scan-barrier-20260913`.

The version-gated Javaagent records only primitive timing/CPU/thread data plus immutable strings and retains no FML objects. It instruments:

- `ModValidator.stage2Validation` (total boundary only),
- `LoadingModList.addForScanning`,
- `BackgroundScanHandler.submitForScanning`,
- `BackgroundScanHandler.addCompletedFile`,
- `BackgroundScanHandler.waitForScanToComplete`,
- an attempted `ModFile.compileContent`/result boundary,
- `ModLoader.gatherAndInitializeMods` as an outer reference.

The injected advice calls only a bootstrap-loaded recorder. Runtime module/version mismatch disables recording. The exact-pack analyzer requires a balanced number of handler submissions/completion callbacks and does not infer worker CPU when `ModFile` is not transform-visible.

`ModFile` method advice was not visible in this transformed runtime, so `compileContent` CPU is intentionally reported as unavailable. This does not prevent critical-path attribution: the handler's completion callback is the terminal stage of each submitted future, and the barrier cannot be waiting on a scan whose callback completed before barrier entry.

## Hosted exact-pack result

Green workflow run: `34754402592` at code head `cb4b55b29d1bf80cd6973337258f7de04b076888`.
Artifact: `agent140-background-scan-profile`, artifact id `10317280162`, digest `sha256:f72056bdb8f055d12a25a59e10f7ee47b85d789dd6231fb43ea8135b9e5f43a0`.

Contract: menu reached in `98,376 ms`; resource selection valid 14/14 in exact order; `reload_count=1`; blocks atlas `8192x8192`, 2 levels; BootOptim mixin errors `0`.

Measured profile:

| Scope | Wall | CPU |
|---|---:|---:|
| `stage2Validation` total | 397.622 ms | 391.623 ms |
| `LoadingModList.addForScanning` | 11.471 ms | 6.991 ms |
| 246 `submitForScanning` calls, sum | 7.438 ms | 4.746 ms |
| 246 `addCompletedFile` callbacks, sum | 49.521 ms | 2.126 ms |
| `waitForScanToComplete` barrier | 0.502 ms | 0.169 ms |

Temporal attribution:

- 246 submissions, 246 completion callbacks, across exactly three `background-scan-handler-*` workers.
- First submission to last completion callback: `3,822.422 ms` envelope.
- Stage-2 exit to last scan completion callback: `3,811.268 ms`.
- Stage-2 exit to barrier entry: `19,705.897 ms`.
- **All 246 completion callbacks occurred before barrier entry.**
- Last scan completion preceded barrier entry by **15,894.629 ms**.
- Therefore scan work contributed **0 ms of actual wait** at the global barrier in this hosted run. The measured barrier method itself cost only `0.502 ms` wall / `0.169 ms` CPU.

The completion-callback wall sum is not scan CPU; it includes synchronized callback waiting. Worker scan CPU was not observed and is not estimated.

## Decision

**No-go for a BackgroundScan launch/barrier optimization on the exact hosted pack.** The stock implementation is already doing the useful overlap: scanning continues for about 3.8 s after Stage 2, but finishes about 15.9 s before the only global scan barrier. There is no scan wait to remove from the menu critical path in this run.

`addForScanning` itself is only ~11.5 ms hosted, so it cannot explain the previously observed hosted Stage-2 residual. More importantly, the valid physical Stage-2 residual (~1.33 s after sorter from the parent investigation) must not be assigned to background scan merely because worker activity starts there: `stage2Validation` returns after submission, while workers continue asynchronously. Physical hardware could change worker duration, but an optimization that changes launch/barrier semantics is not justified without evidence that those workers reach the barrier there.

Do not move the barrier later and do not start scans earlier on this evidence. Both change failure/order/concurrency surfaces for no demonstrated hosted critical-path saving. A future reopening requires a physical/hosted trace showing incomplete handler futures at barrier entry, or a source-level optimization inside the scan work itself with independent correctness and cache identity/invalidation proof.

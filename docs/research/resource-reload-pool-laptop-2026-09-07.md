# Laptop resource-reload executor experiment — 2026-09-07

**Status: LIMITED / REJECTED AS PRODUCTION.** The executor replacement reached the intended reload call and classified the laptop as rotational storage, but the physical A/B did not improve time-to-menu. Keep the measurements and detector lessons; do not promote the pool cap on this evidence.

## Hypothesis

The laptop is a four-thread, HDD-backed machine. Vanilla `Util` creates its background `ForkJoinPool` from logical processors, while `ModelManager` and CITResewn perform many ZIP/model reads. Limiting the resource-reload preparation executor to two workers might reduce HDD seek contention and the long tail attributed to the model pipeline.

The experiment was isolated to the background executor passed by `ReloadableResourceManager.createReload`. It did not move render-thread or apply work. The candidate used a two-worker `ForkJoinPool` with `asyncMode=true`, matching vanilla scheduling semantics, and a fail-open selector with an explicit `-Dboot_optim.resourceReloadThreads=false` kill switch.

## Detection findings

The first detector used a five-sample random-read probe. On the laptop, a warm page-cache probe measured about 23.6 µs and incorrectly classified the disk as fast. A cold `Get-PhysicalDisk` query took about 4.164 s and returned `Unspecified,HDD`; the 4 s timeout therefore also produced false negatives. The revised diagnostic used an 8 s Windows query and cached `ROTATIONAL`/`SOLID_STATE` under `.bootoptim/storage-media-v1.txt`. This made the decision deterministic on subsequent launches, at the cost of a one-time startup query on an uncached machine.

## Physical evidence

The stock control was the clean `resource-target2-laptop` run. The candidate was the final FJP2 build; its startup report proved the mixin and replacement were active:

```text
OPTIMIZATION id=resource_reload_pool status=enabled reason=rotational_storage:threads=2
OPTIMIZATION id=resource_reload_executor status=enabled reason=throttled_executor:max.bg.threads=1
```

| Run | Total to menu | Reload start → FancyMenu finish | Result |
| --- | ---: | ---: | --- |
| Stock control | 330.033 s | 161.786 s | baseline |
| FJP(2) candidate | 334.167 s | 171.392 s | +4.134 s total / +9.606 s reload |

The candidate used only `BootOptim-ResourceReload-1/2` for the relevant preparation; no Mixin or injection error occurred. Its CITResewn load/link interval was 56.575 s and its Decocraft derived report was 2.639 s, so the executor change did not remove the dominant model/CIT work. The result is within the laptop's known noise envelope but is not a win and is directionally worse; it is not production evidence.

Earlier manual worker-count runs are not used as the decision gate because their Prism/JVM state was not a clean paired control:

- one worker: 452.680 s total, 223.092 s reload→menu;
- two workers: 381.210 s total, 189.577 s reload→menu.

They are useful only as a warning that stale Prism arguments and cache state can dominate this laptop's apparent result.

## Compatibility and disposition

The selector was low-end/storage-gated, fail-open, and did not alter game-thread work. Nevertheless, a slower end-to-end result means the cap is rejected as a shipped optimization. The temporary implementation lives only on the local experiment branch `codex/resource-reload-pool`; it must not be merged into `agent/integration-current` without a new premise and a fresh paired physical gate.

The detector/cache idea can be reused by a later hardware-sensitive feature, but its one-time PowerShell cost must be amortized or moved off the critical path before production use.

## Reopening criteria

Reopen this direction only if a new diagnostic proves that (a) the candidate changes a measured critical-path queue/saturation bottleneck, and (b) a clean repeated laptop A/B shows a coherent total-menu or ModelManager-gate improvement. A better target is direct reduction of the 150 s ModelManager/CIT work (prefetch, parsing/cache, or safe decomposition), not another global worker-count guess.

Relevant history: [client model pipeline](model-pipeline.md), [physical variance harness](p0.2-variance-harness-2026-09-06.md), [post-promotion ModelManager residuals](modelmanager-post56-residuals.md), and [PR #151](https://github.com/wachipayox/BootOptim/pull/151).

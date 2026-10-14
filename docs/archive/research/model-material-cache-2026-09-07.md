# Per-model material lookup cache — 2026-09-07

**Status: REJECTED.** The optimized candidate was run through the exact-pack A/B and was substantially slower; the property remains off by default and the change is not production.

## Hypothesis

In Minecraft 1.21.1, `BlockModel.getMaterial(String)` allocates a new reference-chain list and walks the resolved parent chain for every face. The post-promotion ModelManager profiler measured about 1,638 ms of non-FaceBakery `ElementsModel.addQuads` work in the reference pack, with repeated material resolution as the structural suspect. That number is an upper bound from a fast hosted run, not a laptop prediction.

## Candidate

`BlockModelMaterialCacheMixin` memoizes the final `Material` by normalized texture key on each `BlockModel` instance. It only caches after parent resolution (`parentLocation == null || parent != null`), so a transient unresolved-parent lookup remains stock. Models are recreated at the resource-reload boundary, so there is no cross-reload persistence or resource-pack invalidation problem. The candidate is enabled only with:

```text
-Dboot_optim.blockModelMaterialCache=true
```

The default is `false`; disabling it is therefore the fail-open path. No render-thread work is moved and no `Material` is mutated.

## Exact-pack evidence (2026-09-07)

The optimized implementation was run with three fresh candidate/control repetitions. The mixin did activate (the candidate startup report contains `OPTIMIZATION id=block_model_material_cache status=enabled`), with zero Mixin errors and unchanged `8192x8192x2` block atlas dimensions. Despite that, the candidate regressed the median startup substantially:

| Variant | Main menu | Reload → FancyMenu |
| --- | ---: | ---: |
| candidate | 91,853 ms | 40,822 ms |
| control | 69,028 ms | 31,817 ms |
| candidate − control | **+22,825 ms** | **+9,005 ms** |

This confirms that the per-call normalization/cache bookkeeping costs more than the avoided parent-chain lookup for this pack. It is not a candidate for a laptop run or promotion. The earlier naive version had already regressed by +4,508 ms; the lower-overhead rewrite made the negative result even clearer rather than revealing a hidden win.

## Decision gate

The exact-pack A/B must show a coherent median improvement in time-to-main-menu or the ModelManager preparation gate, with no Mixin errors and unchanged atlas/model counts. A result around one second on the hosted pack alone is not sufficient to justify a physical laptop run; the laptop is only warranted if the candidate demonstrates a credible critical-path effect or a larger hardware-sensitive scaling signal.

## Reopening / rejection

Reject if the injection is skipped, if any semantic/visual or atlas count changes, or if the candidate only reduces task-sum CPU while the critical-path wall is unchanged. This experiment is now rejected on direct critical-path regression. Reopen with a different premise only if a later profiler proves material lookup remains a large exclusive cost after removing the duplicated normalization/allocation overhead, rather than by adding another per-call Java cache.

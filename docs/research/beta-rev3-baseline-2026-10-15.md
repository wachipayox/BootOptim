# Beta rev 3 hosted baseline — 2026-10-15

Status: **PROFILED** — baseline only. No optimization is implemented, promoted or rejected here. Three frontiers are listed for a bounded decision; one hot spot repeats a closed decision and is not reopened.

PR: [#360](https://github.com/wachipayox/BootOptim/pull/360) (draft, research only, no runtime change).

## Premise and scope

The Beta pack changed on 2026-10-15: revision 3 = Companion 1.0.5a7, Sinytra Connector and Forgified Fabric API updated, no AutoModPack, CreatePropulsion 1.1.5. Every earlier ledger entry was measured with the September fixture `exact-pack-2026-09-02-v1` (Companion 1.0.5a6). This entry records the rev 3 baseline so that later comparisons use the correct pack.

## Measurement

| Field | Value |
| --- | --- |
| Origin | Hosted exact-pack surrogate (Ubuntu 22.04, Xvfb, llvmpipe), Oracle JDK 25.0.4, workflow base JVM args (`-Xmx6G`, user G1 tuning, `-XX:ActiveProcessorCount=4`) |
| Fixture | `exact-pack-fixture: latest`, profile `profile_wachiland-elite-beta`, revision `rev_7d5b9e504f8422494860364f9312da8e`, manifest `16ebb8d113313eee99a505f9acef3f6c65a406f76c1d5f7d6aa80fb38f986ab4`, ZIP `0e794e39622e025f8c350915b0b3615cce14a7156d480b25c4f89783a6b9f671`, 1,061 files |
| Start / endpoint | process start (JVM) / `main_menu` (not `main_menu_presented`) |
| Runs | **R1** smoke, no JFR: [run 38763952890](https://github.com/wachipayox/BootOptim/actions/runs/38763952890). **R2** smoke with `StartFlightRecording` (`settings=profile`): [run 38766230138](https://github.com/wachipayox/BootOptim/actions/runs/38766230138). R2 perturbs timing: attribution only |
| Reference | September smoke on the legacy fixture, cited from [non-zip-pathpack-enumeration-2026-10-11](non-zip-pathpack-enumeration-2026-10-11.md) (`mod_entrypoint` 31,339 ms, `main_menu` 89,959 ms, reload→FancyMenu 40,969 ms). Different pack, different runner class: orientative only |

Metric types: wall times are inclusive wall from the startup report. CPU and JIT are process-level counters from the `JVM` line (not wall). JFR figures are sampled CPU of Java threads, not wall and not critical path.

## Compatibility

- BootOptim mixin errors: **0** in R1 and R2.
- `[WBOMBO-COMPAT] FALLBACK`: **0** in R1 and R2.
- `[WBOMBO-COMPAT] ACTIVE`: 6 of 6 integrated mechanisms, identical in both runs:

| Mechanism | Mod @ version |
| --- | --- |
| `mcef-first-consumer-defer` | mcef@2.1.6-1.21.1 |
| `decocraft-model-json-batch` | decocraft@3.0.11 |
| `citresewn-glowing-trim-warning-suppression` | citresewn@0 (the log reports version 0; the mod version is not confirmed) |
| `xaero-startup-update-check-skip` | xaeroworldmap@1.41.0 |
| `sodium-axis-quad-flags` | sodium@0.8.12-beta.1+mc1.21.1 |
| `decocraft-quarter-turn-reuse` | decocraft@3.0.11 |

Log noise that is **not** BootOptim (R1 log, not yet reported to Companion):

- two FML `ERROR` lines: access transformer files missing for `mr_toad_palladium` and `pride`;
- about 374 mixin `WARN` lines `Error loading class` for client classes of other mods (Photon, Weather, Gateways, Particular, AE2, WorldEditCUI and others). No crash. No September count exists for comparison.

Per AGENTS.md, a pack bug that is not BootOptim's and is a crash, functional bug or incompatibility belongs in WachilandEliteCompanion. None of these lines was shown to be a crash, so no issue has been filed yet.

## Segments (wall, inclusive)

| Segment | September (legacy, ref.) | rev 3 R1 | rev 3 R2 (JFR) |
| --- | ---: | ---: | ---: |
| `mod_entrypoint` | 31,339 ms | 20,392 ms | 26,619 ms |
| post-entrypoint → `main_menu` | 58,620 ms (derived) | 31,733 ms | 44,032 ms |
| `main_menu` (total) | 89,959 ms | 52,125 ms | 70,651 ms |
| reload → FancyMenu finish | 40,969 ms | 20,911 ms | 29,215 ms |
| FancyMenu panorama | n/a | 2,570 ms | 3,409 ms |

JVM counters, from the startup report `JVM` line:

| Phase | Process CPU | JIT | GC | Heap used |
| --- | ---: | ---: | ---: | ---: |
| `mod_entrypoint` (R1) | 61.5 s | 35.7 s | 1.1 s (61 cycles) | 1.3 GB |
| `main_menu` (R1) | 154.1 s | 59.0 s | 3.1 s (108 cycles) | 3.4 GB |

Reading the table:

- Every rev 3 segment is 30–45 % lower than the September reference. This is **not** a pack effect we can claim. R1 and R2 are the same pack and the same code and differ by 18.5 s on `main_menu` (R2 includes JFR overhead), so runner speed alone spans a range of about 35 %. The 2026-10-14 ledger already records hosted controls from 55 s to 91 s.
- No segment can be called "grew" or "appeared" against September without a same-runner legacy control, which this entry did not run (CI budget: two smoke runs, both used on rev 3).
- The September JIT figure in [jit-compiler-pressure-2026-10-14](jit-compiler-pressure-2026-10-14.md) (103–122 s) came from slow runners and is not comparable with the 59 s above.

## JFR attribution (R2)

Sampled execution events: 5,622 (JFR `profile` sampling, about 10 ms per sample per thread). Samples by thread: Render 1,104; `Worker-ResourceReload-2` 941; `main` 819; `modloading-sync-worker` 573; `Worker-ResourceReload-0` 541; `modloading-worker-0` 301; `Worker-ResourceReload-1` 297; `ForkJoinPool.commonPool-worker-1` 182.

The sample counts are CPU attribution for the sampled threads, not a critical path. The critical path of this pack has not been reconstructed from R2, so the list below is ranked by sampled CPU and by whether the mechanism is new to the ledger.

### Top 5 self-time hot spots

| # | Frame | Samples (≈ CPU) | Where | Ledger status |
| --- | --- | ---: | --- | --- |
| 1 | `FancyMenu Resource.waitForLoadingCompletedOrFailed` | 279 (≈ 2.8 s) | Render thread, inside `ResourcePreLoader.preLoadAll` | **Repeat, do not reopen.** Stock busy wait, measured and rejected as cooperative wait ([fancymenu-cooperative-wait-2026-09-05](../archive/research/fancymenu-cooperative-wait-2026-09-05.md)). |
| 2 | `org.spongepowered.asm.util.Bytecode.getOpcodeName` | 190 (≈ 1.9 s), across all threads | Render 64, main 48, modloading-worker-0 37, pool-8 31 | **Premise changed in magnitude.** The 2026-09-09 opcode-name micro-candidate was NO-GO at 39 samples ([mixin-opcode-name-direct-2026-09-09](../archive/research/mixin-opcode-name-direct-2026-09-09.md)). Still not a frontier without a critical-path link. |
| 3 | Shape join family: `BitSetDiscreteVoxelShape.join`, `LithiumDoublePairList.forMergedIndexes`, `LongOpenHashSet.add` | ≈ 222 self in `LongOpenHashSet.add`, 129 in `forMergedIndexes`; `joinUnoptimized` inclusive 186 (≈ 1.9 s) | modloading-sync-worker, Render, pool-8 | New to the ledger as a pack-level item. Callers: `WallBlock.makeShapes` / `applyWallShape` (26), `CatwalkRailingBlock.getInteractionShape` (7), `CableShapes.getShape` (4), `CrossCollisionBlock`, `PipeBlock`. |
| 4 | Sodium `ColorSRGB.linearToSrgb`, called from vanilla `MipmapGenerator.averageRgb` | 67 (≈ 0.67 s) | `Worker-ResourceReload-2` 62 | New. The ledger never charged mipmap averaging to atlas work ([atlas-sprite-decode-decomposition](../archive/research/atlas-sprite-decode-decomposition-2026-09-01.md): "mipmap work occurs later and must not be charged to this decomposition"). |
| 5 | ASM `ClassReader.readCode` / `readMethod` under `MixinApplicatorStandard` and `ModuleClassLoader.readerToClass` | 67 self, 118–123 inclusive | Render, main, modloading | Repeat family: Mixin / ModLauncher pipeline is closed in the [research index](README.md). Listed for completeness. |

Also present, not in the top 5: Decocraft `BlockbenchBakery.applyElementRotation` (33 self, reload worker) inside the active quarter-turn path; `MoreCulling` `SpriteUtils.doesHaveTranslucency` (39 self, Render), which the ledger already covers in [moreculling-translucency-reuse-2026-09-05](moreculling-translucency-reuse-2026-09-05.md).

## Recommended frontiers (for a bounded decision, not implemented)

Ordered by evidence strength and risk. Each needs a diagnostic before any candidate.

1. **Mipmap sRGB conversion in `MipmapGenerator.averageRgb`** (hot spot 4). Hypothesis: `linearToSrgb` is pure for the finite inputs produced by averaging, so repeated conversions could be served from an exact table or memo with identical output. Segment: reload workers before atlas completion. Ceiling: about 0.67 s sampled CPU per startup, with no GC change (R2 GC is small). Wall effect is unknown until the atlas completion gate is shown to be on the critical path. Gates: exact equality test over every `averageRgb` input in this pack (not a sample), then hosted A/B with two controls and two candidates, per [segment-first policy](segment-first-decision-policy-2026-10-01.md). Risk: low if the function is proven pure; color output must stay bit-identical.
2. **Static shape joins for wall, cross and pipe block states** (hot spot 3). Hypothesis: `makeShapes` / `applyWallShape` recompute the same joins per block state; a memo keyed on the input shapes would remove repeated `joinUnoptimized` work. Segment: modloading and render-thread block registration. Ceiling: about 1.9 s inclusive sampled CPU, of which the wall effect is unknown. This is not the VoxelShaper case rejected in [voxelshaper-safe-domain-2026-09-05](voxelshaper-safe-domain-2026-09-05.md) (Ponder only). Before any candidate, count the join calls per state (the JFR gives no counts) and confirm they are not already deduplicated by Lithium. Risk: shape identity semantics; Lithium and MoreCulling caches are in the same path.
3. **Mixin `getOpcodeName` in the transformation pipeline** (hot spot 2), reopened only on the new magnitude. Do **not** start a candidate unless a critical-path link to the main thread is shown; the 2026-09-09 NO-GO stands for the mechanism, and a larger sample count alone does not reopen it. Priority 3 for that reason.

Not reopened: FancyMenu busy wait (hot spot 1). The 2026-09-05 rejection stands; the same stock wait is a measured CPU cost, but its replacement was rejected for semantic and ordering reasons.

## Reopening criteria

- Frontier 1: an exact conversion argument for `linearToSrgb` over the averaging inputs, plus a critical-path link from the atlas completion to `main_menu` on the hosted or laptop reference.
- Frontier 2: call counts per state proving repeated joins, and proof that the join path is not already cached by Lithium or MoreCulling.
- Frontier 3: a main-thread critical-path measurement showing `getOpcodeName` on the path to `main_menu`.

## Follow-ups

- Frontier 2 (shape joins) was opened and integrated on 2026-10-16: [block-shape-or-memo-2026-10-16](block-shape-or-memo-2026-10-16.md). It also corrects hot spot 3: 76 % of the join samples are Ponder `VoxelShaper`, not the callers listed above.
- The September vs rev 3 difference is not attributable without a control on the same runner. No legacy control was run: a further run on another runner would not settle the ~35 % runner-to-runner variation.
- The two FML access-transformer errors and the `Error loading class` warnings are candidates for a Companion issue only after one is shown to affect the game. Not filed.

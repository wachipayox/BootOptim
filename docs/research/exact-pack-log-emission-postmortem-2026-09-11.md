# Exact-pack startup log-emission post-mortem — 2026-09-11

Status: **PROFILED / NO NEW PRODUCTION LOG FILTER CANDIDATE**

Authority: `agent/integration-current` @ `b3f0c5f6462a359483883741ac16d2f154868900`.

This Agent 130 audit is documentation-only. It adds no logger filter, threshold, config override, resource edit, deduplication hook, Mixin, cache, scheduler change, or runtime behavior change. The one existing user-authorized exception remains the exact CITResewn `Glowing Trim Armors v5.0.zip` legacy-name family promoted by #259; that authorization does not extend to any family below.

## Evidence base and method

The strongest post-mortem corpus is the already-valid #259 hosted exact-pack 3x3 (`34601652989`). Its three **control** artifacts run current head with only the CITResewn kill switch off, so they preserve stock target logging while retaining the same exact-pack software and contract as the promoted build:

- control 1 artifact `10264363945`;
- control 2 artifact `10264368868`;
- control 3 artifact `10264414485`.

All three controls reached the main menu, preserved the exact 14/14 resource-pack selection in order, had one effective resource reload, and had zero BootOptim Mixin failures. The corresponding #259 candidate artifacts were used only as a sanity check that unrelated WARN/ERROR traffic remains visible when the authorized CIT filter is enabled.

The audit groups structured `latest.log` events by logger/level/message family, checks the captured process console separately, and records producer thread and first-to-last event timestamps. A first-to-last span is **not** logger-exclusive wall and is never presented as recoverable startup time. The pack installs AsyncLogger early (`Successfully configured async logger context`), so sink file/console writes can be decoupled from the producer. Producer-side message construction, logger invocation and queueing still execute on the named emitting thread, but this corpus has no exclusive timing that can split those costs from the underlying parse/model/classloading work.

Rendered byte volume was inspected only to distinguish real emissions from counters/probes. **Bytes are not treated as time or savings.** No TTMM claim is made without a candidate A/B.

## Stable top families in all three controls

The family cardinalities below were identical in all three controls.

| family | count | level/logger | producer / observed span | semantic classification | candidate decision |
| --- | ---: | --- | --- | --- | --- |
| CIT legacy `nbt.display.Name` | 7,920 | `ERROR` / `CITResewn` | reload worker; 2.713 / 3.127 / 2.885 s | known compatibility warning, historical severity mismatch | **already handled only by #259 authorization** |
| missing blockstate variants | 310 | `WARN` / `BlockStateModelLoader` | reload worker; 2.246 / 1.917 / 2.055 s overall | real resource/model defects; each variant diagnostic is distinct | **NO-GO for suppression/dedupe** |
| Mixin warnings | 268 | `WARN` / `mixin` | `main`, modloading worker, loader worker; 17.389 / 19.033 / 19.613 s | mixed dev-environment noise plus real compatibility diagnostics | **NO-GO for a pack-wide filter** |
| missing model textures | 146 | `WARN` / `ModelManager` | reload worker; 1–8 ms burst | real model/material defects, model-specific | **NO-GO for suppression/dedupe** |
| empty `PathPackResources` path | 128 | `ERROR` / `PathPackResources` | Render thread; 89 / 151 / 106 ms burst | exact duplicate text, root cause not identified by the event | **decision-map item; no candidate yet** |
| Iris/Veil shader-source capture | 60 | `INFO` / `top.leonx.irisveil.IrisVeilCompat` | 58 Render-thread, 2 reload-worker; ~23 s | source-confirmed debug-like instrumentation at INFO | **decision-map item; upstream cleanup only** |
| DragonLib network registration | 56 | `INFO` / `DragonLib Networking System` | modloading worker; ~0.25–0.30 s | distinct registration progress | **keep** |
| Decocraft startup/model progress | 50 | `INFO` / `com.razz.decocraft.Decocraft` | mixed startup threads; ~26 s | meaningful progress/timing milestones | **keep** |
| missing model references | 44 | `WARN` / `ModelBakery` | reload worker; ~5 s | distinct missing model/resource diagnostics | **keep** |
| missing sound resources | 26 | `WARN` / `SoundManager` | reload worker, same-millisecond burst | distinct missing sound diagnostics | **keep** |

The console additionally contains five small Java 25 restricted-native-access warning blocks. They are JVM stderr warnings around native library access, not a mass Log4j family and not a BootOptim filtering target.

## Family analysis

### 1. Blockstate warnings: 310 real and distinct diagnostics

All three controls contain exactly 310 `BlockStateModelLoader` WARN events. The namespace split is stable:

- `createpropulsion`: 224;
- `dndecor`: 48;
- `picaxe`: 26;
- `webdisplays`: 12.

The largest concrete files are `createpropulsion:tilt_adapter.json` (96 variants), `createpropulsion:advanced_tilt_adapter.json` (96), `dndecor:text_plate.json` (48), `picaxe:image_banner.json` (16), `createpropulsion:coral.json` (16), `createpropulsion:oxidizer.json` (16), and `webdisplays:kb_left.json` (12).

This is not one duplicate warning repeated 310 times: the missing variant key changes. The `createpropulsion` group itself is emitted in only about 62 / 67 / 146 ms in the three current controls despite the larger overall 1.9–2.2 s family envelope, which agrees with #98's earlier conclusion that this count is not a seconds-scale logging ceiling. The warnings run inside actual blockstate/model loading, so their overall envelope cannot be interpreted as emission cost.

**Decision:** preserve. The semantically correct cleanup is to repair the owning blockstate/model definitions if those missing variants are unintended. A logger filter or once-per-file dedupe would hide which variants are missing and therefore violate diagnostic preservation.

### 2. Mixin warnings: 268 events, but heterogeneous semantics

The 268 WARN events split into materially different families:

- 91 unreadable refmap warnings explicitly ending with `If this is a development environment you can ignore this message`;
- 84 `Discarding @Unique ...` collisions, all from Copycats in this corpus;
- 58 `Error loading class ... ClassNotFoundException` diagnostics;
- 29 missing `@Mixin target` warnings across several mods;
- 2 overwrite conflicts;
- 2 static-binding violations;
- 2 `@ModifyConstant` conflicts.

The hosted exact-pack launch target is `forgeclientdev`, so the 91 refmap warnings are specifically development-environment noise in this CI surrogate. That does **not** authorize a runtime suppression candidate: changing Mixin refmap/remapping options would alter transformation semantics rather than merely control output, while a message filter would be a broad development-only Log4j policy and would not apply cleanly to the real Prism workload. The remaining 177 warnings are genuine compatibility/transformation diagnostics and must remain visible.

**Decision:** no BootOptim candidate. Treat the 91 refmap records as a hosted-dev interpretation caveat, not a production startup optimization.

### 3. ModelManager: 146 missing-texture warnings plus one real load error

The 146 WARN events are dominated by `displaydelight` (132), followed by `tfmg` (5), `sable_schematic_api` (5), `create_power_loader` (2), `decocraft` (1), and `we_companion` (1). The file log renders 294 physical lines because each warning can carry a continuation naming the missing atlas texture. For Display Delight the continuation `minecraft:textures/atlas/blocks.png:displaydelight:block/bowl` is repeated many times, but the parent model identifiers differ.

The same logger also reports a separate real `ERROR` for failure to load `tfmg:models/block/large_transformer/meow.json`. Any logger-level or texture-prefix filter would therefore be unacceptable, and deduplicating only the repeated continuation would mutilate multi-line warning context rather than remove a duplicate semantic event.

The complete 146-warning burst is only 1–8 ms wide in the current controls; this is workload evidence, not an exclusive logger cost measurement.

**Decision:** preserve all model warnings/errors. Fix resource definitions upstream/pack-side if desired; no log candidate.

### 4. `PathPackResources`: 128 identical `ERROR` events

All three controls contain exactly 128 copies of:

```text
Invalid path : Invalid path ''
```

They occur on the Render thread immediately after Chloride logs `Registering CHLORIDE built-in packs`, before the initial `Reloading ResourceManager` line. The 128-event burst spans only 89 / 151 / 106 ms across the controls.

This is the strongest **cardinality** candidate after CIT because the rendered message is byte-for-byte identical. It is not yet a safe dedupe candidate because the event contains no pack id, path owner, stack, or call-site identity. One warning could represent one caller looping 128 times, or 128 independent bad icon/root/resource requests with the same empty path. Preserving only the first would erase that distinction.

Public source inspection provides useful but insufficient ownership clues:

- Chloride's `FastBlocks.registerResourcePacks` creates two built-in `PathPackResources` at the immediately adjacent timestamp.
- Veil's NeoForge `PathPackResourcesMixin` extends pack resources with `veil$getIcon()`; its `ForgePackHooks.getIcon(root)` reads `neoforge.mods.toml` `logoFile` metadata and then calls `getRootResource(...)`. The inspected Veil source does not special-case a blank logo path.

These establish plausible pack/icon paths through `PathPackResources`, but **do not identify the exact 128 control events**. Two Chloride packs also cannot explain a count of 128 by themselves.

There is no official Minecraft/NeoForge configuration found that suppresses only this message while preserving all other `PathPackResources` errors. An exact-message Log4j filter would still be global across every pack/caller and is outside the user's CIT-specific authorization.

**Decision: no candidate now.** This remains decision-map item A. The only defensible reopening is to add diagnostic-only caller/pack attribution (or obtain an upstream stack-bearing reproduction), then fix the owning blank path or dedupe in that owning source if all 128 are proven to be the same semantic warning. Do not filter it in BootOptim from current evidence.

### 5. Iris/Veil compatibility INFO: source-confirmed debug-like emission

The exact controls contain 60 INFO events from `top.leonx.irisveil.IrisVeilCompat`; 58 are on the Render thread and two are client-setup messages on a reload worker. The shader events include lines such as `Captured vertex source ... chars, hasGetVelocity=..., hasOffset=...` and fragment equivalents. Eleven identical vertex-source captures for `veil:blit_screen` appear in one control, but most records name different shader stages/sources.

Public `iris-veil-compat` source at commit `062cd7f2ff90736e89e2576a5a0a88cd412ef117` confirms `MixinDirectShaderCompiler` unconditionally logs an INFO for every captured vertex/fragment source while also storing that processed source in the compatibility cache. Thus the log statement is instrumentation/progress output inside real shader compilation; removing the line would not remove shader compilation or the cache store.

No source option was found that turns only these capture INFO lines off. A pack-level Log4j filter would be unnecessary and outside authorization. The clean source-level policy, if the owning mod is deliberately updated/forked, would be to move these capture diagnostics from INFO to DEBUG (or add an owner-controlled verbose flag), preserving WARN/ERROR and all shader/cache behavior.

**Decision:** decision-map item B, not a BootOptim candidate. This is the best upstream logging-cleanliness issue in the remaining corpus, but only 60 events and no A/B establish material TTMM leverage.

### 6. INFO progress families are not spam candidates

DragonLib emits 56 distinct network-packet registration messages during actual protocol registration. Decocraft emits 50 startup/model progress lines including batch boundaries and measured load totals. Their counts are stable, but their messages carry distinct identifiers or useful lifecycle milestones.

**Decision:** keep. Neither is a duplicate warning family, and no count-based optimization premise exists.

### 7. Remaining WARN families should stay visible

`ModelBakery` emits 44 missing-model WARN events over about five seconds of interleaved model work; `SoundManager` emits 26 missing-resource warnings in a tight worker burst. These are real resource integrity diagnostics. Their family spans are not logging-only duration, and suppressing them would hide actionable missing assets.

## Critical-path interpretation

This audit can safely answer **where producer-side logging occurs**, but not how much TTMM the appenders own:

- Render-thread producer: the 128 `PathPackResources` errors and nearly all Iris/Veil capture INFO events are emitted on a serial client thread. Their logger-call overhead is therefore not automatically overlapped, but the observed envelopes include the underlying pack/shader work.
- Resource-reload producer: blockstate, ModelManager, ModelBakery, SoundManager and CIT messages are emitted by resource-reload workers inside parsing/model/resource work. Their first-to-last spans are heavily interleaved with that real work.
- Loader/modloading producer: Mixin and DragonLib emissions occur while transformation/mod construction is executing; their 17–20 s or sub-second envelopes are not logging attribution.
- Async sinks: AsyncLogger is active before these families, so file/console rendering and write latency cannot be equated to producer critical-path wall from timestamps alone.

Accordingly this report makes **no recoverable-wall estimate from line counts, bytes or emission spans**. A future candidate would require its own comparable exact-pack A/B after a semantic proof.

## Candidate decision matrix

### Production candidates created: **0**

No new family satisfies all three required conditions simultaneously: (1) redundant semantics are proven, (2) official/source-local scoping preserves every other warning/error, and (3) current authorization covers the change.

### Decision-map item A — 128 empty-path errors

Potential value: remove a genuinely identical repeated error only **after** proving a single owning root cause. Required evidence: pack/caller identity for all 128 events. Preferred remedy: fix the blank path or dedupe at that owner while keeping one warning and all unrelated `PathPackResources` errors. Current action: none.

### Decision-map item B — Iris/Veil capture INFO

Potential value: logging cleanliness, not demonstrated TTMM. Source semantics are complete enough to recommend an upstream INFO→DEBUG/verbose-policy change, but the exact-pack mod must be fingerprinted and the owner/update lane explicitly authorized before changing it. Current action: none.

## Relationship to prior work

- #97 established that exact downstream Log4j interception can be mechanically narrow, but that does not generalize authorization.
- #258 proved the CIT legacy family consists of real logger events and rejected broad CIT configuration.
- #259 is the sole current user-authorized exception and validates exact matcher fail-open behavior.
- #98 previously decomposed the same 310 blockstate warnings and already rejected count-based warning spam as a startup target; the fresh #259 controls reproduce the same cardinality and further bound the 224 Create Propulsion subgroup to a short burst.

## Final decision and reopening rule

**NO-GO on any additional BootOptim logging filter or global logging configuration.** Keep the existing #259 CIT exception exactly scoped. The remaining high-count WARN/ERROR families are either distinct actionable diagnostics or lack sufficient owner identity to deduplicate safely; the main INFO families are meaningful progress or low-volume debug-like output.

Reopen only with one of these materially new premises:

1. the 128 empty-path errors are attributed to one exact owner/call site and that owner exposes or accepts a once-per-root warning policy preserving every other error;
2. the exact Iris/Veil compatibility binary is fingerprinted and an authorized owner/update lane permits moving shader-capture INFO to DEBUG without runtime behavior changes; or
3. another future exact-pack corpus reveals a new repeated **semantic** event family, not merely a high-count logger, and its owner/configuration permits targeted dedupe while preserving all other warnings/errors.

A reopened implementation must first prove semantic scope and then run a normal exact-pack gate/A-B. Do not infer TTMM from byte reduction, line count, or the timestamp envelope recorded here.

# Exact-pack mod-specific startup/reload audit — 2026-09-04

Status: **PROFILED / CIT LANE ACTIVE / NO NEW PRODUCTION CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

This audit looks for startup/reload work owned by a specific mod rather than reopening generic ModelManager, Mixin, atlas, MCEF, FancyMenu or Decocraft ideas. It follows the project rule that CPU/task-sum/inclusive wall are not equivalent to recoverable critical-path wall or TTMM.

## Inputs and scope

Reviewed before proposing work:

- root `AGENTS.md`, project README, research ledger and production-optimization ledger;
- open and closed BootOptim PR history, including the retained/rejected ModelManager, Mixin/ModLauncher, FancyMenu, MCEF, Decocraft, shape, renderer and resource-reload lanes;
- hosted exact-pack fixture and current exact-pack logs/artifacts;
- exact pack mod inventory (160 JARs in the inspected smoke);
- source/ownership where a concrete candidate appeared.

Primary raw-log sample for the counts below: exact-pack run `33917611497` from the current EMF diagnostic lane. Log timestamps are used only as observation windows unless an instrumented profiler proves exclusivity/critical-path contribution.

The older laptop JFR remains useful for hotspot discovery, but this audit does not reinterpret its sample percentages as TTMM savings.

## Result summary

| Area | Exact-pack evidence | Ownership / change lane | Decision |
| --- | --- | --- | --- |
| CITResewn + `Glowing Trim Armors v5.0.zip` | 7,920 legacy-name ERROR events during initial reload; ~2.211 s emission window in run `33917611497` | CITResewn is not user-controlled; BootOptim compatibility only | **ACTIVE** as PR #97 A/B logging-pressure ceiling; parser/open decomposition only if that fails |
| Furnish | exact pack loads Fabric Furnish 29 through Connector | `wachipayox/FurnishPorted` is user-controlled and contains a NeoForge platform, but checked branch is MC 1.21 / mod version 27 rather than exact v29 / 1.21.1 | **HOLD**: first recover exact v29 source parity; then A/B native NeoForge vs Fabric/Connector only if per-mod Connector cost is measurable |
| Wachiland Elite Companion | no source-level eager file/JSON/native load found in constructor; heavier compatibility registration is already deferred to load-complete | user-controlled | **NO EXPERIMENT** without timing evidence |
| JEFA / Wakes-Sable / Sable-SA compatibility repos | no file/JSON eager-load pattern found by source search; no exact-log timing signal | user-controlled | **NO EXPERIMENT** without timing evidence |
| Blockstate-miss diagnostics | 310 total warnings, but 224 Create Propulsion warnings occur in only ~54 ms; DnD 48 in one timestamp; PicAxe 26 in ~1 ms; WebDisplays 12 in ~1 ms | third-party in the checked repositories | **REJECT AS PRIMARY TARGET**: warning volume is visually noisy but current observation ceiling is tiny |
| Invalid resource paths | 128 `PathPackResources` warnings in ~85 ms | mixed pack content | **REJECT AS PRIMARY TARGET** |
| NeoForge update checks | sequential HTTP checks are visible for several mods, but all run on `NeoForge Version Check` thread during reload | framework/third-party | **NO DEFER CLAIM**: no evidence they gate TTMM |
| KubeJS/scripts | fixture copies script directories, but no KubeJS mod is loaded in the inspected exact pack | n/a | **NOT A RUNTIME FRONT** for this exact pack snapshot |
| Decocraft registry/model bootstrap | dynamic block registration reports 3,527 entries; 635 unique model loads; 1,231 ms model load / 1,295 ms block registration | third-party | **KNOWN LARGE WORK, NOT A NEW LANE**; existing Decocraft resource/model/read-ahead history applies |
| MCEF native init | present, but already has dedicated overlap and first-consumer work | third-party + BootOptim compatibility | **CURRENT FRONT; NOT DUPLICATED** |
| EMF repeated compile / renderer apply | dedicated open diagnostics already exist | third-party + BootOptim diagnostic | **CURRENT FRONTS; NOT DUPLICATED** |

## 1. CITResewn: the new actionable mod-specific front

### Exact-pack evidence

In current hosted exact-pack logs, CITResewn emits 7,920 `Using legacy nbt.display.Name` events from `Glowing Trim Armors v5.0.zip` during the initial resource reload. In run `33917611497`, the burst spans approximately `20:45:59.486` to `20:46:01.697`, about **2.211 s wall on a resource-reload worker**.

This is an observation window, not a 2.211 s savings claim: CIT preparation overlaps other reload work and async logging can move formatting/writing away from the caller.

Source inspection establishes an unusually clean semantic boundary. The legacy `nbt.display.Name` form is converted to the modern custom-name condition first; `CITResewn.logWarnLoading(...)` then emits the diagnostic. Therefore a narrow logging-pressure ceiling can be measured without skipping CIT enumeration, rule construction, model preparation or linking.

### Existing diagnostic — do not duplicate

PR #97, `Diagnostic: test CIT legacy-warning reload pressure`, already owns this lane. It starts from the current integration SHA and requests hosted 3x3 A/B:

```text
-Dboot_optim.experimentCitLegacyWarningFilter=true
-Dboot_optim.experimentCitLegacyWarningFilter=false
```

The candidate is intentionally diagnostic-only: it temporarily denies only CITResewn `ERROR` events whose message contains `Using legacy nbt.display.Name`, counts them, removes the filter at first `TitleScreen`, and leaves CIT resource/model semantics untouched.

At the time of this audit, normal Build and Startup Benchmark checks are green; the Exact Pack Startup Benchmark is still queued. Do not promote the filter from warning count alone.

### Escalation if logging is not material

If PR #97 suppresses ~7,920 events but TTMM/reload medians are tied/noisy/regressive, reject logging pressure and profile CIT source-level phases separately:

1. pack enumeration;
2. ZIP entry open/inflate/read/decode;
3. Java properties parse;
4. legacy-name conversion;
5. condition/type construction;
6. item-model preparation/linking;
7. current-thread CPU versus inclusive wall and TTMM critical-path effect.

Only if stable per-entry ZIP opens/parses are proven critical should a cache be tested. The safe shape is a fail-open cache of immutable decoded property payloads keyed by exact CIT artifact + ordered pack/content identity, with fresh CIT runtime/model objects on every reload. A miss must execute stock code; stale/corrupt cache must fall back to stock; manual resource reload after pack changes must not reuse stale data.

## 2. Furnish: controlled fork, but the exact artifact does not match the checked source branch

Ownership verification found `wachipayox/FurnishPorted` under the authenticated owner account. Its checked `1.21` branch is explicitly multi-platform and includes a `neoforge/` subproject.

However, that branch currently declares:

```text
mod_version = 27
minecraft_version = 1.21
enabled_platforms = fabric,neoforge,quilt
```

The exact pack, by contrast, loads `Furnish 29` from a file labelled Fabric/1.21.1 and NeoForge initially reports the JAR as a Fabric mod before Connector loads it. The runtime later logs `Hello Fabric world!`.

That mismatch matters. It is not safe to claim that simply building the current NeoForge subproject is an equivalent replacement for the exact pack artifact.

### Reopening gate

A direct-mod experiment is justified only after exact v29/1.21.1 source parity is recovered or reconstructed and semantic/resource parity can be proven. Then measure a pack A/B between:

- exact Fabric v29 through Connector; and
- equivalent native NeoForge v29.

Do **not** assume this removes Connector startup: Joy of Painting and Music Maker are also Fabric JARs in the exact pack. The experiment must therefore measure Furnish-specific transform/adapter savings, not market the whole observed Connector discovery interval as Furnish savings.

This is a good direct-mod lane only if attribution shows material per-mod work.

## 3. Controlled compatibility mods: no eager-I/O target found yet

### Wachiland Elite Companion

Source inspection of `wachipayox/WachilandEliteCompanion` found a constructor dominated by registration and compatibility flag setup. It does not expose an obvious eager file/JSON/native-resource load. Several compatibility/device/partial-model actions are already moved to `FMLLoadCompleteEvent`.

That makes an additional speculative defer risky: it could move work toward first-world entry without a measured TTMM win. No branch is justified until instrumentation identifies a real inclusive/critical span.

### JEFA, Wakes-Sable and Sable-SA compatibility

A source search over the controlled compatibility repositories did not find the obvious `FileReader` / bulk `Files.read*` / Gson/JSON eager-load pattern requested by this audit. The exact log also does not expose a timing marker attributable to them.

No direct-mod change is justified from static code size or ownership alone.

## 4. Blockstate/model warning storms: noisy, but not a useful wall target in this sample

The inspected run has 310 `BlockStateModelLoader` warnings. Initial grouping made this look like a WebDisplays problem; namespace decomposition disproves that:

- Create Propulsion: **224**, emitted in about **54 ms** (`20:46:04.925`–`.979`);
- Design n' Decor: **48**, all at the same millisecond in the log;
- PicAxe: **26**, about **1 ms**;
- WebDisplays: **12**, about **1 ms**.

These messages can indicate resource correctness issues, but their current emission windows are far below the CIT lane and do not justify a BootOptim suppression/filter. The 1.5 s span between first and last `BlockStateModelLoader` warning was simply interleaved model work from different namespaces; it must not be attributed to those warnings.

Create Propulsion is not user-owned in the checked account/repository set, so any source fix would also be upstream/third-party unless ownership changes.

## 5. Other requested categories

### Initializers / registries

Decocraft remains the standout explicit registry bootstrap: it reports 3,527 dynamic blocks, 635 unique model loads, 1,231 ms model loading and 1,295 ms complete block registration. That is real mod-internal work, but it is already adjacent to heavily investigated Decocraft resource/model fronts. The later gap before `RegisterEvent<Item>` is not attributable to Decocraft from log silence alone.

Do not create another Decocraft cache/read-ahead experiment without a materially new source-level premise and exact critical-path evidence.

### Native/resource loads

MCEF remains the material native-load front, but it already has dedicated experiments. The hosted fixture pre-seeds MCEF libraries, so hosted native timing is not a substitute for the Windows laptop gate.

Large physical image/resource reads remain represented by the existing atlas/Decocraft/FancyMenu research. This audit found no second mod-specific native loader with comparable evidence.

### Scripts

Although the fixture includes copied `kubejs/` and script directories, no KubeJS mod is present in the loaded 160-JAR inventory of the inspected run. Script optimization is therefore not a TTMM candidate for this exact snapshot unless the fixture/mod inventory changes.

### Configs

Config libraries emit normal setup messages, but this pass did not find a repeated config parse with source-level attribution and a measurable critical-path window. Do not cache configs generically.

### Network/update callbacks

NeoForge performs several HTTP update checks serially on a dedicated `NeoForge Version Check` thread while resource reload is in progress. Their wall duration is visible but there is no evidence that title waits for that thread. They are not a safe TTMM target without a dependency/gate proof.

## Priority order after this audit

1. **Finish PR #97 A/B**. If it loses/ties, profile CIT ZIP/open/parse phases; if it wins, replace the diagnostic filter with the narrowest semantically acceptable source/compat mechanism and re-test.
2. **Recover Furnish v29/1.21.1 source parity** before doing any native-NeoForge replacement experiment. Measure per-mod Connector cost; do not assign global Connector time to Furnish.
3. **Do not pursue the blockstate warning groups for performance** based on current data; their local observation ceilings are tiny.
4. **Keep WEC/JEFA/Wakes-Sable/Sable-SA unchanged** until direct timing identifies a concrete eager workload.
5. Continue the already-open MCEF/EMF/renderer/Decocraft lanes under their own documented gates rather than duplicating them here.

## Safety / gameplay gate

For any future direct-mod or BootOptim compatibility experiment from this audit:

- first computation/loading remains stock unless an A/B explicitly tests a safe equivalent;
- fail open on version/source mismatch;
- no persistent mutable runtime/model objects across reloads;
- no change to user JVM/system settings;
- defer only if first-world/first-use latency stays acceptable and semantic/visual behavior is unchanged;
- compare TTMM and reload critical-path wall, not only CPU/task-sum counters;
- hosted exact-pack can screen Java/resource semantics, but storage/native/GPU claims still require the real hardware gate defined by `AGENTS.md`.

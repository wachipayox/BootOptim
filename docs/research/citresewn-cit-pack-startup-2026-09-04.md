# CITResewn / large CIT pack startup front — 2026-09-04

Status: **ACTIVE DIAGNOSTIC / NO PRODUCTION CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

This is a new resource/asset-preparation lane. It deliberately does **not** revisit MCEF first-consumer, FancyMenu panorama supplier overlap, Decocraft, ModelManager/model-bake caches, generic resource-listener timing, or the rejected resource/model experiments already recorded in the research ledger.

## Why this lane exists

A current hosted exact-pack smoke from PR #95, workflow run `33911857477`, reached:

- main menu: `88.158 s`;
- mod entrypoint: `29.684 s`;
- post-entrypoint: `58.474 s`;
- initial reload -> FancyMenu finish: `38.829 s`;
- FancyMenu panorama preload: `4.075 s`.

The same raw console shows a previously untracked exact-pack asset workload from CITResewn and `file/Glowing Trim Armors v5.0.zip`:

- initial `ReloadableResourceManager` reload starts at `19:37:37`;
- first `Using legacy nbt.display.Name` diagnostic appears at `19:37:44`;
- **7,920** such CITResewn ERROR events are emitted, corresponding to **7,920 unique `.properties` resource paths**;
- every one of those 7,920 events is attributed to `file/Glowing Trim Armors v5.0.zip` and `Worker-ResourceReload-1`;
- second-level counts are 1,069 at `19:37:44`, 1,538 at `:45`, 2,025 at `:46`, and 3,288 at `:47`; there are no later legacy-name warnings in the run;
- the 7,920 lines occupy about **1.76 MiB** of the `2.23 MiB` raw startup console;
- CITResewn next logs `Loading item CIT models...` at `19:37:52` — the roughly five-second gap after the warning burst is **not** attributed to logging by this research;
- CITResewn logs `Linking baked models to item CITs...` at `19:38:03`;
- stock atlas upload starts around `19:38:06`;
- FancyMenu's ordered reload turn starts around `19:38:12`;
- title/main-menu marker is at `19:38:16`.

This is not yet a claim that CITResewn contributes four, five, eleven, or twenty-six seconds of recoverable wall time. The warning burst and later CIT preparation overlap other work. The relevant question is whether the repeated diagnostics delay the global preparation gate or consume enough of the four-core runner's logging/heap/CPU budget to lengthen end-to-end reload and time-to-main-menu.

The mechanism is materially different from the older BootOptim resource work:

- PRs #69/#71/#72 measured ModelManager and atlas resource paths; this lane is a third-party CIT resource-pack parser/model-preparation workload.
- PR #76 tested one physical **Decocraft JAR read-ahead** premise; this lane concerns thousands of `.properties` entries in an enabled user resource-pack ZIP and repeated diagnostics during their semantic parse.
- PR #74 established that ModernFix 5.27.14's `PathPackResourcesMixin` does not provide a live general path-pack acceleration in this snapshot, while `FilePackResourcesMixin` is BETA-gated. Any later resource-pack index must still check the exact effective ModernFix state before duplicating it.

## Hypotheses

### H1 — diagnostic logging pressure is material

CITResewn emits 7,920 nearly identical ERROR events from one resource-reload worker while the initial preparation is active. Even with asynchronous logging in the pack, the caller still creates/queues events and the process must eventually format/write roughly 1.76 MiB of repeated console data during startup.

A narrow A/B can test this without touching resource semantics.

Candidate property:

```text
-Dboot_optim.experimentCitLegacyWarningFilter=true
```

Candidate behavior:

- installs a Log4j context filter from the BootOptim client entrypoint, before the initial client resource reload;
- denies only `ERROR` events from a logger whose name contains `citresewn` and whose message contains `Using legacy nbt.display.Name`;
- counts denied events;
- removes the filter at the first `TitleScreen` opening;
- emits one `BOOTOPTIM_CIT_LEGACY_WARNING_FILTER` completion marker with the denied count;
- does not change CIT resource enumeration, ZIP access, `.properties` parsing, condition construction, model loading/linking, activation, ordering, threads, resource resolution or resulting models.

This is a **ceiling test**, not a proposed production logging policy. Individual compatibility diagnostics are intentionally hidden in the candidate and therefore the branch must not be merged as-is.

### H2 — the real cost is per-entry ZIP open / properties decode / parse

If H1 produces little or no TTMM movement while the candidate still suppresses about 7,920 events, the warning storm is evidence of workload size but not the bottleneck.

The next diagnostic should then target the exact CITResewn 1.21.1 parser/fork shape and split, for the initial reload only:

1. CIT resource enumeration by pack;
2. resource open / byte read / character decode;
3. `Properties`/syntax parse;
4. legacy-name compatibility conversion;
5. condition/type construction;
6. item-model preparation/linking;
7. current-thread CPU versus wall.

Do not infer a persistent cache from rule count alone.

### H3 — a persistent parsed-properties bundle may be viable

Only if H2 proves thousands of stable ZIP entry opens/parses are on the main-menu critical path should BootOptim test a cache.

A safe first cache would **not** persist CIT runtime/model objects. It would persist a compact immutable representation of the decoded `.properties` key/value payloads, then let CITResewn perform its normal version-specific condition/type/model construction on every reload. The goal would be to replace thousands of independent ZIP entry opens/inflations with one sequential cache read, not to freeze mutable registries or model state across reloads.

Required cache key/invalidation inputs include at least:

- exact CITResewn artifact identity/version;
- Minecraft/loader environment relevant to syntax semantics;
- ordered enabled resource-pack identity;
- physical pack path plus robust content fingerprint (not timestamp alone);
- entry path plus CRC/size or equivalent content identity;
- cache format version.

The first calculation on a cache miss must remain stock, writes must be atomic, corruption must fail open to stock, and a manual/subsequent resource reload after any pack change must not reuse stale data.

This cache premise is intentionally narrower than a generic Minecraft resource cache and materially different from the rejected Decocraft read-ahead experiment.

### H4 — resource-pack ZIP indexing may dominate instead

If enumeration/open rather than content parse dominates, inspect ModernFix effective state first. PR #74 records that `perf.resourcepacks.FilePackResourcesMixin` is BETA-gated in ModernFix 5.27.14 and that option/category state alone does not prove application. Do not build a duplicate ZIP index until the exact pack's transformed target/effective feature level is confirmed.

## Critical-path gate

Do not rank this lane from inclusive CIT task time or log timestamps alone.

The candidate/control decision uses paired exact-pack **time-to-main-menu** and `reload -> FancyMenu finish` medians. The suppression count is only a mechanism check.

Recommended hosted gate:

```text
[exact-pack-ci]
exact-pack-mode: ab
exact-pack-repetitions: 3
exact-pack-candidate-jvm-arg: -Dboot_optim.experimentCitLegacyWarningFilter=true
exact-pack-control-jvm-arg: -Dboot_optim.experimentCitLegacyWarningFilter=false
```

Interpretation:

- candidate must suppress approximately the observed 7,920 legacy events and still reach title with 0 BootOptim Mixin errors;
- a favorable single run is insufficient;
- compare medians and the direction of both main-menu and reload metrics;
- if the candidate is tied/noisy or regresses, reject H1 and do not promote a log filter;
- if the candidate wins materially, prefer fixing/coalescing the diagnostic at the CITResewn/resource-pack layer over shipping a generic BootOptim Log4j policy, then reconfirm with a separate implementation.

Hosted CI is adequate for this logging/Java-pressure ceiling. Any later claim about physical ZIP/disk latency or page-cache behavior still needs the real Windows laptop per `AGENTS.md`.

## Risks

- **Diagnostic visibility:** candidate hides per-file legacy warnings. This is why it is diagnostic-only and self-removes at title.
- **Logger implementation:** a Log4j implementation change can make the context filter unavailable. Installation is fail-open and reports `status=unavailable`.
- **Match drift:** if CITResewn changes logger/message text, suppression count will be zero rather than broadening to unrelated errors.
- **Observer effect:** the filter itself adds one predicate to startup log events. Control keeps the class present but disabled; only paired A/B is meaningful.
- **False attribution:** CIT model loading/linking overlaps ModelManager preparation. Only end-to-end paired wall metrics can establish leverage.
- **Persistent-cache compatibility:** future caching must preserve pack ordering/override semantics, manual reload invalidation, current registry/model construction, and corrupted-cache fallback.

## Stop / continue rules

**STOP H1** if the exact-pack 3x3 is tied/regressive despite the expected suppression count.

**CONTINUE to H2** if H1 is a no-op but CIT remains temporally aligned with the preparation window: source-profile the exact parser instead of guessing.

**CONTINUE to H3/H4** only after H2 identifies a material main-menu critical-path ceiling in repeated ZIP open/decode/parse or enumeration, respectively.

No production cache or log suppression is justified by the evidence currently recorded in this document.

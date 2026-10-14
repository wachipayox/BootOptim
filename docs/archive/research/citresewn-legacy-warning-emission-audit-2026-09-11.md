# CITResewn legacy-warning emission audit — 2026-09-11

Status: **CLOSED / NO COMPLIANT LOGGING CANDIDATE**

Authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This Agent 125 audit is documentation-only. It adds no CIT logic change, cache, scheduler, resource rewrite, logger filter, global logging configuration, error suppression, or runtime Mixin. It specifically resolves the 7,920 diagnostics called out by PR #257 and checks whether a logging-only candidate can satisfy the stricter requirement that real errors, warnings, fallbacks, and parsing failures remain visible.

## Evidence base

Required history:

- PR #151 — retained reload-local base-item-model parse/open cache. It is active in the current exact pack and is unrelated to this logging lane.
- PR #221 — valid hosted exact-pack smoke `34295167385`, head `138f45a18529693dafb4e2ce939de07ad84e2426`.
- PR #257 — attributes the early CITResewn lifecycle residual and records the 7,920-diagnostic / 2.886 s observed span.
- Historical PR #97 — already implemented and exercised an exact-logger Log4j filter for precisely this message family.
- Historical PR #104 — resource-pack key migration experiment; mechanically valid, but no stable hosted startup win was established.

The exact-pack fixture remains `exact-pack-2026-09-02-v1`, `bootoptim-exact-pack.zip`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

## What the 7,920 diagnostics actually are

They are **not BootOptim counters, probe records, synthetic profiler events, or an estimate of internal calls**. They are 7,920 real CITResewn Log4j events and 7,920 real rendered lines in the exact-pack logs.

Direct inspection of artifact `exact-pack-result-smoke-1` from run `34295167385` shows:

- `run-pack-benchmark/logs/latest.log`: exactly **7,920** matching lines;
- `exact-pack-console.log`: exactly **7,920** matching lines;
- first file-log line: `00:31:55.212`;
- last file-log line: `00:31:58.098`;
- observed first-to-last span: **2.886 s**;
- every event logger: exactly `CITResewn`;
- every event level: exactly `ERROR` in the deployed lineage;
- every event thread: the same `Worker-ResourceReload-2` worker;
- every message: `Using legacy nbt.display.Name ...`;
- every source pack: `file/Glowing Trim Armors v5.0.zip`;
- **7,920 unique resource paths**;
- matching rendered bytes including newlines: **1,873,692 bytes** in `latest.log` and **1,762,812 bytes** in the captured console;
- total CITResewn `ERROR` events in `latest.log`: **7,920**; there are no additional CITResewn `ERROR` messages in this valid artifact.

The console and file copies therefore represent separate output sinks for the same 7,920 logger events; they must not be counted as 15,840 CIT parsing operations.

The 2.886 s is only the wall interval between the first and last warning. Parsing and logging are interleaved, so it is neither a logging-only duration nor a recoverable savings estimate.

## Source-level route: parsing, formatting, logger call, emission

The public historical `shcm.shsupercm...` 1.21.1 lineage matches the exact runtime package shape exercised by BootOptim's #151 bridge. In `ConditionComponents.load`, a legacy `nbt.display.Name` key is first translated to the modern `minecraft:custom_name` component form, then the warning text is constructed and logged:

```text
ConditionComponents.load
  -> properties.messageWithDescriptorOf("Using legacy nbt.display.Name", position)
  -> CITResewn.logWarnLoading(formattedString)
  -> LOG.error("[citresewn] " + formattedString)
  -> Log4j appenders / sinks
```

This distinction answers the requested attribution:

1. **Counter/probe:** no. There is no BootOptim counter generating these records.
2. **Logger invocation:** yes. There is one `CITResewn.logWarnLoading(...)` call per affected property in this lineage.
3. **Serialization/formatting:** yes, but small and unavoidable by a downstream logger filter. `messageWithDescriptorOf(...)` creates the full message string (line number, resource id, pack descriptor) before Java can enter `logWarnLoading`; `logWarnLoading` then prefixes it before invoking Log4j. A logger/appender filter cannot retroactively avoid the already-completed property parsing or descriptor-string construction.
4. **Lines emitted:** yes. The artifact contains all 7,920 lines in both the normal file log and captured console.

The legacy conversion itself occurs before the log call. Removing an output event would not remove the condition conversion, registry lookup, fallback matcher construction, CIT object construction, resource resolution, model loading, or gameplay behavior.

## Important severity mismatch

The historical source documents `logWarnLoading` as a **pack-loading warning** and exposes it under `CITResewnConfig.mute_warns`, but that lineage implements the method with `LOG.error(...)`. Thus the exact artifact's `ERROR` level does not make these parser failures; they are compatibility/deprecation warnings that happen to be emitted at error severity.

Actual loading errors use the separate `logErrorLoading(...)` path and `mute_errors` configuration. This is why filtering by Log4j level alone cannot distinguish warning-vs-error semantics in the deployed lineage.

## Official CITResewn configuration

The historical 1.21.1 config exposes:

```json
{
  "mute_errors": false,
  "mute_warns": false
}
```

with both defaults false.

`mute_warns=true` is the official switch that would prevent the `LOG.error` call for these legacy-name warnings. It is **not acceptable for BootOptim under this task** because it suppresses every CITResewn pack-loading warning routed through `logWarnLoading`, not only this repeated legacy-name family. That can hide unrelated compatibility warnings and warning-order evidence. `mute_errors` must remain false and is not relevant to this family.

There is no historical config option that mutes only `nbt.display.Name` warnings while preserving all other warnings.

Because the descriptor string is evaluated before `logWarnLoading`, even the official `mute_warns` switch would still leave `messageWithDescriptorOf(...)` formatting and all parsing/conversion work intact; only the subsequent CITResewn prefix/logger/appender path is avoided.

## Strictly directed Log4j filtering was already tested (#97)

PR #97 already built the narrowest practical downstream filter for this exact workload. After Connector had created the logger, it attached a filter to the concrete core logger named exactly `CITResewn` and denied only events satisfying both:

- level exactly `ERROR`;
- message prefix exactly `[citresewn] Using legacy nbt.display.Name`.

Everything else returned `NEUTRAL`. The passing proof smoke `33927253818` showed `suppressed=7920`, zero target lines in both file and console, title reached, and zero BootOptim Mixin errors. Its later hosted 3x3 (`33927602940`) reported median candidate-control deltas of `-2.149 s` to main menu and `-1.025 s` reload-to-FancyMenu, but those historical numbers are not promoted here as a current savings claim.

That mechanism answers whether a non-global, message-specific filter is technically possible: **yes**. It does not answer the current semantic gate positively. The source itself classifies these records as warnings, and the present assignment explicitly forbids suppressing real warnings. A filter that removes 7,920 of them, however precise, therefore fails before performance validation.

There is also no runtime evidence in the #221 artifact for preservation of a separate genuine CITResewn parsing error because this particular valid run contains no other CITResewn `ERROR` event. Source-level predicate analysis proves #97 would return `NEUTRAL` for a nonmatching message, but that does not convert a warning-suppressing mechanism into a compliant candidate.

## Current upstream behavior is different, but not the exact-pack binary

The public current NeoForge source previously examined in #223/#257 (`CancriRecoleta/CITResewn@8cca0f127f3898472e2109f6ac6a797253756893`) has already changed this policy internally:

- `logWarnLoading` uses `LOG.warn`, not `LOG.error`;
- a reload-scoped `logWarnLoadingOnce(onceKey, message)` deduplicates warning kinds;
- `ConditionComponents` keys legacy-name warning deduplication by `packName + "#nbt.display.Name"` and emits the warning at most once per pack/loading pass;
- the dedupe set is explicitly cleared for the next loading pass.

That is an upstream source-level policy for warning cardinality, not a BootOptim configuration option. The exact fixture reports `CITResewn 0` and demonstrably exercises the historical `schm.shsupercm...` bridge, so this newer implementation is not claimed as the deployed JAR and must not be silently backported by BootOptim.

## Candidate decision table

### `mute_warns=true`

**Reject.** Official and reversible, but too broad: it suppresses all CITResewn loading warnings. Violates the explicit warning-preservation requirement.

### Global Log4j threshold/filter

**Reject.** Violates the no-global-filter requirement and would risk unrelated diagnostics/errors.

### Exact `CITResewn` logger + exact legacy-name prefix filter

**Reject for production under the current contract.** It is technically narrow and #97 proved the mechanism, but it still suppresses 7,920 real warning events. It also does not avoid message construction or CIT parsing, only downstream logging/output.

### Rewrite/migrate the resource pack keys

**Reject for this task.** PR #104 already tested the exact semantic migration. It changes third-party resource bytes, is not a logging configuration, and did not establish a stable hosted startup win.

### Backport current upstream once-per-pack warning dedupe

**Reject as a BootOptim workaround.** It changes visible third-party warning cardinality and exact source behavior. Consider only through a fingerprinted CITResewn upgrade/direct source lane, not an external runtime filter.

## Decision

**NO-GO: the 7,920 records are real emitted CITResewn compatibility warnings, not profiler noise. No current official configuration or BootOptim-local filter satisfies the requirement to reduce them while preserving all warnings/errors/fallbacks/parsing failures.**

Therefore this branch does **not** request a new exact-pack smoke or A/B. The task's ordering requires a semantically compliant logging candidate before CI; none exists. Re-running #97 would only repeat a mechanism that is now explicitly disallowed by the warning-preservation gate, and no performance claim is made from the 2.886 s span or the historical #97 median.

Reopen only if one of these premises changes:

1. the exact pack upgrades/fingerprints CITResewn to a version whose own documented warning policy deduplicates this family, and that version passes the normal exact-pack resource/reload/gameplay gates; or
2. CITResewn itself exposes a versioned option specifically for this warning family while preserving all other warnings/errors and reload semantics; or
3. the product requirement explicitly changes to permit suppressing/deduplicating this compatibility-warning family, in which case #97 is the prior mechanism/evidence to revisit rather than designing another filter.

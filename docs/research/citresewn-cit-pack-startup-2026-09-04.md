# CITResewn / large CIT pack startup front — 2026-09-04

Status: **PROOF OF MECHANISM PASSED / VALID A/B AUTHORIZED / NO PRODUCTION CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

This lane is limited to the exact pack's CITResewn legacy-name diagnostic workload. It does **not** reopen MCEF first-consumer, FancyMenu panorama, Decocraft, ModelManager/model-bake caches, or rejected generic resource-cache experiments.

## Original exact-pack signal

Hosted exact-pack smoke `33911857477` exposed a previously untracked workload during the initial client resource reload:

- **7,920** `Using legacy nbt.display.Name` diagnostics;
- **7,920 unique `.properties` paths**;
- all from `file/Glowing Trim Armors v5.0.zip`;
- all on a resource-reload worker;
- approximately **1.76 MiB** of a `2.23 MiB` raw startup console.

These values establish workload size only. They are not a recoverable-wall or TTMM claim because CIT work overlaps broader resource/model preparation.

## Source-level logging route

The exact event shape is:

```text
[Worker-ResourceReload-*/ERROR] [CITResewn/]: [citresewn] Using legacy nbt.display.Name ...
```

CITResewn 1.21.x routes it through:

```text
ConditionComponents.load(...)
  -> CITResewn.logWarnLoading(message)
  -> CITResewn.LOG.error("[citresewn] " + message)
```

The semantic legacy conversion has already happened before logging. A diagnostic can therefore suppress only this resulting logging event while leaving parsing, conversion, CIT construction, resource resolution and model behavior stock.

## Invalid A/B: `33924322432`

**Do not interpret any candidate/control timing delta from this run.**

All three candidates reached title but reported `suppressed=0`, while all 7,920 matching diagnostics remained visible. The candidate installed a Log4j `Configuration.addFilter(...)` obtained from BootOptim's current context. The real event already had logger `CITResewn`, level `ERROR`, and the expected text, so broader matching was not the fix; the selected configuration was not on the effective CITResewn logging path.

The campaign is invalid at the mechanism layer, independently of its wall-clock numbers.

## Failed proof smoke: `33926596142`

PR #97 was reduced to a one-run candidate smoke before another A/B. The proof gate rejected it, so its timing is also non-interpretable.

The artifact showed all **7,920** target diagnostics still visible, completion `suppressed=0`, and an early Mixin error:

```text
Error loading class: shcm/shsupercm/fabric/citresewn/CITResewn
(java.lang.ClassNotFoundException: shcm.shsupercm.fabric.citresewn.CITResewn)
```

CITResewn is a Fabric mod loaded by **Sinytra Connector 2.0.0-beta.17+1.21.1**. Connector invokes Fabric `main` and `client` entrypoints after normal Mixin configuration preparation. The attempted `@Pseudo` mixin therefore skipped the absent target and was not retried later.

Observed ordering in that failed smoke:

```text
22:44:53.202 BootOptim mod entrypoint
22:44:55.304 CITResewn Registering CIT Conditions
22:44:55.332 CITResewn Registering CIT Types
22:45:11.800 initial Reloading ResourceManager
22:45:20.259 first legacy-name diagnostic
```

The ineffective `@Pseudo` hook was removed.

## Corrected mechanism

Property:

```text
-Dboot_optim.experimentCitLegacyWarningFilter=true
```

BootOptim registers a mod-bus listener and waits until `FMLCommonSetupEvent`, after Connector has invoked the Fabric entrypoints in this exact pack. It then:

1. obtains Log4j's core `Log4jContextFactory`;
2. enumerates all active core `LoggerContext`s;
3. selects contexts which already contain a logger named exactly `CITResewn`;
4. requires exactly one match;
5. attaches a filter directly to that concrete core logger instance.

The direct logger filter denies only events satisfying both:

```text
level == ERROR
message starts with "[citresewn] Using legacy nbt.display.Name"
```

Everything else from CITResewn and every other logger stays visible. The filter disarms at first `TitleScreen`. It does not skip/cache/reorder CIT enumeration, ZIP access, `.properties` parsing, legacy conversion, condition/type construction, model loading/linking, resource resolution or bake results.

## Mandatory proof gate

When the diagnostic property is enabled, `scripts/exact-pack/run_startup.py` rejects the run unless all conditions hold:

1. exactly one `BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=armed` marker;
2. exactly one completion marker;
3. suppression within `7,920 ± 120` (`7,800..8,040`);
4. zero `Using legacy nbt.display.Name` diagnostics visible in `latest.log`;
5. main-menu marker reached;
6. zero BootOptim Mixin failures.

The runner therefore rejects broken mechanisms before their wall times can enter an interpretable campaign.

## Passing proof smoke: `33927253818`

**Proof of mechanism passed.** Exact-pack artifact `exact-pack-result-smoke-1` independently verifies:

```text
BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=waiting interception=direct_citresewn_logger
BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=armed interception=direct_citresewn_logger logger=CITResewn context=AsyncDefault expected=7920
BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=complete reason=title_screen suppressed=7920 expected=7920
```

Artifact checks:

- suppression: **7,920 / 7,920 exact**;
- visible `Using legacy nbt.display.Name` in `latest.log`: **0**;
- visible target diagnostics in captured console: **0**;
- selected logger: exactly `CITResewn`;
- selected context: `AsyncDefault`;
- main menu reached;
- Build for the corrected branch: **green**;
- benchmark smoke job: **success**.

This proves interception only. Its elapsed time is **not** used as an optimization result.

## Valid A/B gate now opened

Because proof passed, PR #97 may now request the intended hosted 3x3:

```text
[exact-pack-ci]
exact-pack-mode: ab
exact-pack-repetitions: 3
exact-pack-candidate-jvm-arg: -Dboot_optim.experimentCitLegacyWarningFilter=true
exact-pack-control-jvm-arg: -Dboot_optim.experimentCitLegacyWarningFilter=false
```

The valid campaign must be judged by end-to-end critical-path metrics:

- main-menu median;
- initial reload -> FancyMenu-finish median;
- candidate suppression near 7,920 and zero residual target diagnostics;
- zero BootOptim Mixin failures.

A suppression count alone is never a performance result. If the valid 3x3 is tied/noisy/regressive, reject diagnostic logging pressure as an optimization target and do not promote this filter.

## Cache work explicitly deferred

Do **not** implement a CIT cache in PR #97. The presence of 7,920 parsed files is not sufficient evidence that ZIP/open/decode/parse lies on the main-menu critical path.

A separate parser/IO decomposition can only be reconsidered after H1 has a valid result and would require a new premise and new branch. No parsed-properties cache is implemented or requested here.

## Risks

- **Diagnostic visibility:** the candidate intentionally hides only the target compatibility diagnostics; this is why it remains diagnostic-only.
- **Context selection:** zero or multiple matching `CITResewn` logger contexts fail closed for the experiment; the exact-pack gate rejects the run.
- **Installation race:** common setup runs concurrently with reload preparation. Any missed target diagnostic makes the zero-visible gate reject the candidate.
- **Match drift:** an upstream message or level change fails the suppression-count gate instead of broadening interception.
- **False attribution:** even a valid suppression mechanism must still move TTMM/reload wall coherently before H1 has performance value.

## Current decision

- `33924322432`: **INVALID — NO DELTA INTERPRETATION**.
- `33926596142`: **FAILED PROOF — NO TIMING INTERPRETATION**.
- `33927253818`: **PROOF PASSED — 7,920 SUPPRESSED, 0 VISIBLE**.

Next allowed action: **valid hosted 3x3 A/B only**. The filter remains diagnostic, not production, and CIT cache work remains out of scope.

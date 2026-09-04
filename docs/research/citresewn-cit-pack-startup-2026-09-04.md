# CITResewn / large CIT pack startup front — 2026-09-04

Status: **PROOF-OF-MECHANISM REQUIRED / PRIOR A/B INVALID / NO PRODUCTION CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

This lane is limited to the exact pack's CITResewn legacy-name diagnostic workload. It does **not** reopen MCEF first-consumer, FancyMenu panorama, Decocraft, ModelManager/model-bake caches, or rejected generic resource-cache experiments.

## Original exact-pack signal

Hosted exact-pack smoke `33911857477` reached main menu at `88.158 s` and showed a previously untracked CITResewn workload during the initial client resource reload:

- **7,920** `Using legacy nbt.display.Name` diagnostics;
- **7,920 unique `.properties` paths**;
- all from `file/Glowing Trim Armors v5.0.zip`;
- all on `Worker-ResourceReload-1`;
- approximately **1.76 MiB** of a `2.23 MiB` raw startup console;
- warning burst visible from `19:37:44` through `19:37:47`;
- `Loading item CIT models...` follows later at `19:37:52`;
- `Linking baked models to item CITs...` follows at `19:38:03`.

These timestamps establish workload size only. They are not a recoverable-wall or TTMM claim because CIT work overlaps the broader ModelManager/resource preparation path.

## Source-level logging route

The exact visible event shape in `latest.log` is:

```text
[Worker-ResourceReload-1/ERROR] [CITResewn/]: [citresewn] Using legacy nbt.display.Name ...
```

CITResewn 1.21.x centralizes this path in:

```text
ConditionComponents.load(...)
  -> CITResewn.logWarnLoading(message)
  -> CITResewn.LOG.error("[citresewn] " + message)
```

The semantic legacy conversion has already happened before `logWarnLoading` is called. Therefore cancelling only this emission helper for the exact legacy-name message is a narrow diagnostic ceiling: parsing, conversion, CIT construction, resource resolution and model behavior remain stock.

## Invalid A/B: `33924322432`

**Do not interpret any candidate/control timing delta from this run.**

All three candidates reached title but reported:

```text
BOOTOPTIM_CIT_LEGACY_WARNING_FILTER ... suppressed=0
```

while all 7,920 matching CITResewn diagnostics remained visible.

The previous candidate installed a Log4j `Configuration.addFilter(...)` selected from BootOptim's context. That filter did not participate in the effective CITResewn logging route in the exact pack. Broadening logger-name/message matching would not fix the mechanism: the actual logger was already exactly `CITResewn` and the actual message already contained the expected text.

The campaign is therefore invalid at the mechanism layer, independently of its wall-clock numbers.

## Corrected diagnostic mechanism

Property remains:

```text
-Dboot_optim.experimentCitLegacyWarningFilter=true
```

The candidate no longer installs a global/context Log4j filter. It uses an optional `@Pseudo` mixin targeting only:

```text
shcm.shsupercm.fabric.citresewn.CITResewn.logWarnLoading(String)
```

At method entry it cancels the call only when the argument begins exactly with:

```text
Using legacy nbt.display.Name
```

It counts each cancellation and disarms at the first `TitleScreen`.

Consequences:

- unrelated CITResewn warnings/errors still execute normally;
- unrelated Log4j loggers are untouched;
- CIT enumeration and ZIP access are untouched;
- `.properties` decode/parse and legacy conversion are untouched;
- condition/type construction is untouched;
- item-CIT model loading/linking and baking are untouched;
- this remains **diagnostic-only** and is not a production logging policy.

## Mandatory proof gate

No A/B is valid until an exact-pack candidate smoke proves the interception mechanism.

When the diagnostic property is enabled, `scripts/exact-pack/run_startup.py` now rejects the run unless all conditions hold:

1. exactly one `BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=armed` marker;
2. exactly one completion marker;
3. suppression is within `7,920 ± 120` (`7,800..8,040`);
4. zero `Using legacy nbt.display.Name` diagnostics remain visible in `latest.log`;
5. main-menu marker is reached;
6. zero BootOptim Mixin failures are detected.

The invalid `33924322432` candidate artifact is rejected by this gate rather than entering a performance summary.

PR #97 is intentionally configured as a **single candidate smoke** while proof is pending:

```text
[exact-pack-ci]
exact-pack-mode: smoke
exact-pack-smoke-jvm-arg: -Dboot_optim.experimentCitLegacyWarningFilter=true
```

## A/B only after proof

Only after a smoke passes the mechanism gate may PR #97 return to:

```text
[exact-pack-ci]
exact-pack-mode: ab
exact-pack-repetitions: 3
exact-pack-candidate-jvm-arg: -Dboot_optim.experimentCitLegacyWarningFilter=true
exact-pack-control-jvm-arg: -Dboot_optim.experimentCitLegacyWarningFilter=false
```

Then H1 is judged by end-to-end critical-path metrics:

- main-menu median;
- initial reload -> FancyMenu-finish median;
- candidate mechanism markers and zero residual matching warnings;
- zero BootOptim Mixin failures.

A suppression count alone is never a performance result. If the valid 3x3 is tied/noisy/regressive, reject diagnostic logging pressure as an optimization target and do not promote this filter/mixin.

## Cache work explicitly deferred

Do **not** implement a CIT cache while the logging-pressure experiment is being repaired and re-gated. The presence of 7,920 parsed files is not sufficient evidence that ZIP/open/decode/parse lies on the main-menu critical path.

A separate parser/IO decomposition can only be reconsidered after H1 has a valid result and would require a new premise and new branch. No parsed-properties cache is implemented or requested by PR #97.

## Risks

- **Diagnostic visibility:** the candidate intentionally hides these compatibility diagnostics, hence diagnostic-only status.
- **Optional-target compatibility:** the `@Pseudo` hook must remain harmless when CITResewn is absent; normal Build/Startup CI must stay green.
- **Match drift:** an upstream message change should fail the suppression-count gate instead of widening interception.
- **Observer effect:** candidate/control share the same optional mixin class; only the JVM property changes cancellation behavior.
- **False attribution:** even a valid suppression mechanism must still move TTMM/reload wall coherently before H1 has performance value.

## Current decision

`33924322432`: **INVALID — NO DELTA INTERPRETATION**.

Next allowed action: **exact-pack candidate proof smoke only**. A/B is blocked until suppression is near 7,920 with zero residual matching diagnostics.

# CITResewn / large CIT pack startup front — 2026-09-04

Status: **PROOF-OF-MECHANISM REQUIRED / PRIOR A/B INVALID / NO PRODUCTION CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

This lane is limited to the exact pack's CITResewn legacy-name diagnostic workload. It does **not** reopen MCEF first-consumer, FancyMenu panorama, Decocraft, ModelManager/model-bake caches, or rejected generic resource-cache experiments.

## Original exact-pack signal

Hosted exact-pack smoke `33911857477` reached main menu at `88.158 s` and showed a previously untracked CITResewn workload during the initial client resource reload:

- **7,920** `Using legacy nbt.display.Name` diagnostics;
- **7,920 unique `.properties` paths**;
- all from `file/Glowing Trim Armors v5.0.zip`;
- all on a resource-reload worker;
- approximately **1.76 MiB** of a `2.23 MiB` raw startup console.

These timestamps establish workload size only. They are not a recoverable-wall or TTMM claim because CIT work overlaps the broader ModelManager/resource preparation path.

## Source-level logging route

The exact visible event shape is:

```text
[Worker-ResourceReload-*/ERROR] [CITResewn/]: [citresewn] Using legacy nbt.display.Name ...
```

CITResewn 1.21.x centralizes this path in:

```text
ConditionComponents.load(...)
  -> CITResewn.logWarnLoading(message)
  -> CITResewn.LOG.error("[citresewn] " + message)
```

The semantic legacy conversion has already happened before logging. A diagnostic may therefore suppress only the resulting logging event while leaving parsing, conversion, CIT construction, resource resolution and model behavior stock.

## Invalid A/B: `33924322432`

**Do not interpret any candidate/control timing delta from this run.**

All three candidates reached title but reported `suppressed=0`, while all 7,920 matching diagnostics remained visible. The candidate had installed a Log4j `Configuration.addFilter(...)` obtained from BootOptim's current context. The real event already had logger `CITResewn`, level `ERROR`, and the expected text, so broader matching was not the fix; the selected configuration was not on the effective CITResewn logging path.

The campaign is invalid at the mechanism layer, independently of its wall-clock numbers.

## Failed proof smoke: `33926596142`

PR #97 was correctly changed to a one-run candidate smoke before another A/B. The new proof gate rejected the smoke, so no timing result is usable.

Evidence from the artifact:

- `BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=armed ...` was emitted by BootOptim;
- all **7,920** legacy-name diagnostics remained visible;
- completion reported `suppressed=0`;
- Mixin had already logged:

```text
Error loading class: shcm/shsupercm/fabric/citresewn/CITResewn
(java.lang.ClassNotFoundException: shcm.shsupercm.fabric.citresewn.CITResewn)
```

The attempted `@Pseudo` mixin was therefore prepared too early. In this exact pack CITResewn is a Fabric mod loaded by **Sinytra Connector 2.0.0-beta.17+1.21.1**. Connector invokes Fabric `main` and `client` entrypoints during its early mod-loading phase, but BootOptim's normal Mixin configuration resolves optional targets before that Fabric class is visible. `@Pseudo` skips the missing class and is not retried later.

Observed ordering in the failed smoke:

```text
22:44:53.202 BootOptim mod entrypoint
22:44:55.304 CITResewn Registering CIT Conditions
22:44:55.332 CITResewn Registering CIT Types
22:45:11.800 initial Reloading ResourceManager
22:45:20.259 first legacy-name diagnostic
```

NeoForge 1.21.1 source also establishes that `FMLCommonSetupEvent` is dispatched from `ClientModLoader` during the initial reload, after mod gathering/initialization and before load completion. This supplies a later, Connector-safe installation point with several seconds of observed margin before the CIT diagnostic burst. The proof gate still treats any missed early diagnostics as failure.

## Current corrected diagnostic mechanism

Property remains:

```text
-Dboot_optim.experimentCitLegacyWarningFilter=true
```

The ineffective CITResewn `@Pseudo` mixin has been removed.

BootOptim now registers a mod-bus listener and waits until `FMLCommonSetupEvent`. At that point CITResewn has already created its Log4j logger in the exact pack. The diagnostic:

1. obtains Log4j's core `Log4jContextFactory`;
2. enumerates **all active core LoggerContexts**;
3. selects contexts which already contain a logger named exactly `CITResewn`;
4. requires exactly one match;
5. attaches a filter directly to that concrete core logger instance.

The direct logger filter denies only events satisfying both:

```text
level == ERROR
message starts with "[citresewn] Using legacy nbt.display.Name"
```

Everything else from CITResewn and every other logger stays visible. The filter disarms at first `TitleScreen`.

This route deliberately avoids both failed assumptions:

- it does not use BootOptim's own `LoggerContext` as a proxy for CITResewn's context;
- it does not require CITResewn's Fabric class to exist during BootOptim Mixin preparation.

## Mandatory proof gate

No A/B is valid until an exact-pack candidate smoke proves the interception mechanism.

When the diagnostic property is enabled, `scripts/exact-pack/run_startup.py` rejects the run unless all conditions hold:

1. exactly one `BOOTOPTIM_CIT_LEGACY_WARNING_FILTER status=armed` marker;
2. exactly one completion marker;
3. suppression is within `7,920 ± 120` (`7,800..8,040`);
4. zero `Using legacy nbt.display.Name` diagnostics remain visible in `latest.log`;
5. main-menu marker is reached;
6. zero BootOptim Mixin failures are detected.

The runner therefore rejects both known broken mechanisms rather than allowing their wall times into an interpretable campaign.

PR #97 remains intentionally configured as a **single candidate smoke** while proof is pending:

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

A suppression count alone is never a performance result. If the valid 3x3 is tied/noisy/regressive, reject diagnostic logging pressure as an optimization target and do not promote this filter.

## Cache work explicitly deferred

Do **not** implement a CIT cache while the logging-pressure experiment is being repaired and re-gated. The presence of 7,920 parsed files is not sufficient evidence that ZIP/open/decode/parse lies on the main-menu critical path.

A separate parser/IO decomposition can only be reconsidered after H1 has a valid result and would require a new premise and new branch. No parsed-properties cache is implemented or requested by PR #97.

## Risks

- **Diagnostic visibility:** the candidate intentionally hides only the target compatibility diagnostics; this is why it remains diagnostic-only.
- **Context selection:** zero or multiple matching `CITResewn` logger contexts fail closed for the experiment; the exact-pack gate then rejects the run.
- **Installation race:** common setup runs concurrently with reload preparation. If any target diagnostic is emitted before attachment, the zero-visible gate rejects the smoke rather than treating partial suppression as proof.
- **Match drift:** an upstream message or level change fails the suppression-count gate instead of broadening interception.
- **False attribution:** even a valid suppression mechanism must still move TTMM/reload wall coherently before H1 has performance value.

## Current decision

- `33924322432`: **INVALID — NO DELTA INTERPRETATION**.
- `33926596142`: **FAILED PROOF — PSEUDO MIXIN TARGET UNAVAILABLE EARLY; NO TIMING INTERPRETATION**.

Next allowed action: **exact-pack candidate proof smoke only**. A/B remains blocked until suppression is near 7,920 with zero residual matching diagnostics.

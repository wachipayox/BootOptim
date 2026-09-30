# Autonomous physical-laptop express sweep

This is a prepared diagnostic campaign, not production and not an authorized
laptop launch yet. User asked for serial unattended collection while Codex
works elsewhere. No SSH or laptop writes occur in local `Prepare`.

Base refreshed: integration `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.
Candidate optimization files copied without modification from #296 Decocraft
V2, #298 FerriteCore capacity, #299 Sodium and #301 per-layer arithmetic.
Exclude their JFR, per-quad clock/count probes, UV equivalence mixins and
historical repeated-reload automation. The Decocraft support helper's dormant
verification/counters remain off, as in its tested branch. The combined
optional mixin layout itself needs hosted runtime validation.

## Measurement and automation

Five processes use one identical packaged JAR: all candidates OFF control,
then Decocraft V2, FerriteCore, Sodium and layer arithmetic individually ON.
Fixed startup markers and one first-client-resource-generation future are
recorded. A small diagnostic observes only Minecraft's own resource manager;
it waits for that future, no overlay, a rendered title frame and two stable
seconds before shutdown. It never enters a world. A prematurely exposed RRLS
title does not satisfy the complete initial future/presentation gate.
First title opening and full-generation menu presentation are separate.

Optional `-IncludeMenuReload` requests one stock reload through
`Minecraft.reloadResourcePacks()` **in the menu**, then waits for its returned
future plus overlay-free title presentation before exit. It does not press
F3+T, change resource selection or enter a world. This is useful for FerriteCore
capacity's next-generation premise; startup alone cannot test that premise.
The prepared local bundle includes this mode. In-world F3+T/visual and
first-world gates remain exclusively on the fast PC because the laptop crashes
at world entry. User must leave the automatic game/Prism windows alone.

Clocks: JVM uptime to complete-generation `main_menu_presented`; optional
menu reload separately invocation→future completion in monotonic nanoseconds.
GC count/time are process totals, including the repeat if enabled, not exclusive
startup CPU. Launcher preparation, transaction and cleanup times are outside
those boundaries. No per-quad clock, JFR or frame-stack polling is included.
There is one run per configuration, fixed order and uncontrolled HDD/page-cache
state: this is **exploratory**, not proof of small wins. Repeat paired controls
for any promotion. Five six-minute startups alone take ~30 minutes; including
menu reloads takes longer, so 20 minutes is not a guarantee.

## Controller and evidence

`tools/laptop-bench/express_sweep.ps1` supports Prepare / Start / Status / Collect.
Start copies the local bundle and dispatches a Windows scheduled controller
task, so losing this chat or SSH does not interrupt serial runs. It runs each
game in the verified active interactive session via the existing transaction
helper. No Codex sleeps are needed. Controller task execution limit is three
hours; each JVM gets a 1200-second timeout plus launch appearance grace.

Preflight verifies computer identity, configured Oracle Java 25.0.4, sole
packaged BootOptim wrapper, JAR hash, no existing Prism/target Java and effective
required args. It preserves user GC and production tuning, replaces only known
candidate/profiling/benchmark switches and rejects other active experiments or
heavy tracing. It does not silently upgrade the JVM or overwrite an open Prism
configuration. The older local connection note's JDK21/IP218 is stale; defaults
use the user's later IP139, and actual configured Java is checked at dispatch.

The controller uses an exclusive instance lock. Each run gets separate
preflight JSON, original options, new latest.log/startup log, effective-process
transaction evidence, result JSON and restoration record. Named previous logs
are moved into that run's directory rather than deleted. State/result writes
are atomic. A failed run aborts the remaining sweep; recovery terminates only
the owned PID with matching creation identity, waits for Prism closure and
restores the original JAR/instance.cfg from verified backups. It retains partial
evidence. It does not silently restore changed resource-pack choices and call
that a success: drift is a failed run requiring inspection.

Collect copies the completed evidence to the PC and produces a table and JSON.
Offline gate checks all five configurations, restoration, single JAR hash,
effective process identity, resource-pack order in options **and actual reload
logs**, generation count, effective independent feature flags, target activation where instrumented and BootOptim Mixin failures. Missing or failed evidence
is invalid. It does not infer physical F3+T from a menu reload.

## Local validation / next gate

Packaged build passed. PowerShell parsing passed. Offline transaction doubles
passed success, failure-after-one-run and menu-repeat scenarios: independent
flag matrix, five separate outputs, fail-fast, original config restoration and
lock cleanup. Five offline summary tests passed, including effective resource
fallback and partial failure rejection. No game or SSH was used in these tests.
Corrected hosted control/all-enabled automatic menu-repeat gate passed below.
Next: the user's signal before touching the laptop. Do not call the full remote
Windows task lifecycle physically validated merely from local doubles or Linux CI.

### Hosted gate review: 36728663450 (2026-09-30)

At source `f2fabc52`, both variants compiled and reached the initial full-generation
menu without BootOptim Mixin failures. Effective resource selection matched the
fixture and the blocks atlas was 8192x8192x2. Candidate Decocraft V2, FerriteCore
capacity and Sodium activation markers were present. However, the workflow's
green status was **not a valid autonomous-cycle gate**: each latest.log contained
only `initial_reload_created` and `initial_reload_complete`, one generation, and
no rendered-menu/repeat/final sweep markers. The normal `exitOnTitle` route stopped
the client immediately after title opening; Gradle's run config supplies that
property even though extra JVM args requested false. No menu repeat ran.

Hosted initial complete-generation uptime was 86,085 ms control and 91,501 ms
all-enabled candidate; first title opening was 86,204 / 91,633 ms in latest.log.
These single independent VM runs are composition checks, not an attributed speed
win. In particular they say nothing about retained capacity's next generation.
Evidence: Actions run 36728663450 artifacts and local `express-endpoint-review.json`.

The diagnostic now owns shutdown while expressSweep is enabled, regardless of
the legacy exitOnTitle setting; ordinary startup-only benchmarks keep their
existing behavior. The hosted runner also rejects an opted-in express campaign
unless the exact initial/presented/repeat/final stage sequence and generation
count are present. Seven offline tests cover premature closure, failed repeat,
duplicate final marker, extra generation and invalid endpoint order. Build passed.
The original green status alone must not authorize a laptop campaign; the corrected
cycle below is the runtime gate. No laptop contact occurred during this review.

### Corrected hosted gate: 36731187366 (2026-09-30)

Source `bccb5ee5baccc4f90714fa3218b21fc244671652`: build, startup, PowerShell
syntax and exact-pack jobs PASS. Both latest.logs contain the exact six-stage
sequence: initial created, initial complete, menu presented, menu reload requested,
menu reload complete, finished; shutdown follows finished. Each has exactly two
actual ResourceManager generations with the fixture's effective pack order intact,
no resource fallback and zero BootOptim Mixin failures. Candidate activation for
Decocraft V2, FerriteCore capacity (both generations) and Sodium is recorded.
The independent offline lifecycle validator also passed the downloaded logs.

| Variant | Initial complete JVM ms | Rendered menu JVM ms | Menu invocation-to-future ms | Finished JVM ms | Whole-process GC ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| All OFF | 88,364 | 91,241 | 31,257.673 | 159,654 | 6,265 |
| All ON | 90,812 | 124,515 | 30,860.052 | 161,488 | 6,846 |

Origin is hosted Linux exact-pack, not physical laptop or in-world F3+T. These
independent single processes validate composition/lifecycle, not speed or attribution.
MCEF's first video consumer initialized for about 31.724 / 30.810 seconds at
different points: after the repeat in control versus before presentation in
candidate. Consequently the rendered-menu delta is especially confounded by native
video timing and the randomly selected background. Existing fixture EMF model
creation warnings remain; this gate is not visual equivalence of the user's newer
FancyMenu fork. No optimization promotion follows from these runs.

Evidence: [Actions run](https://github.com/wachipayox/BootOptim/actions/runs/36731187366),
downloaded artifacts under `C:\BootOptimBench\analysis-reload-20260930\express-sweep-36731187366`
and `express-endpoint-review.json`. Packaged local bundle hash below is unchanged.
Physical Windows scheduled-task dispatch/recovery remains unvalidated until the
authorized laptop sweep. No SSH/laptop staging or launch has occurred.

Local bundle: `C:\BootOptimBench\artifacts\express-sweep-20260930\bundle`.
Packaged JAR SHA256:
`3C986500743BA83F30F165D1EC84BCDD5D38D75586BD91CEA63BB9E5D62120AF`.
Source and gates also live in the [persistent candidate register](express-candidates-laptop-register-2026-09-30.md).

## Physical dispatch and harness repairs — 2026-09-30

User authorized background dispatch. Actual Prism instance override selects Oracle
Java **21.0.9**, not the hosted baseline 25.0.4. Original campaign stopped at that
preflight before staging. Subsequent physical campaigns retain the configured JVM
and GC arguments identically across modes; this is an explicitly recorded baseline
deviation, never a comparison against hosted Java25 or historical laptop timings.

Two harness defects were found before valid measurement:

- `express-sweep-20260930-java21`: QSettings stored `JvmArgs` inside enclosing
  quotes. ConfigValue retained them; transaction escaping made them literal JVM
  quotes. User saw "Could not create the java virtual machine"; no game JVM was
  tracked. Original config/JAR restoration passed. Parser now strips only the
  serialization wrapper before unescaping. Four offline controller scenarios
  (including quoted baseline + stale exitOnTitle) PASS. Direct physical decoded
  baseline `java.exe -version` returned exit0 with Java21.0.9.
- `express-sweep-20260930-qfix`: game Java did start, but both helpers compared
  raw command lines against backslash paths. Prism used forward slashes, so the
  controller failed to recognize it and timed out. PID6888 had the expected parent
  Prism7640, active session8, expected executable and matching normalized instance
  path. It was explicitly terminated after fresh creation-time verification before
  recovery; do not count that run as a benchmark. Both Target-Java helpers now
  normalize separators before matching. Offline real-function AST tests cover
  forward/backslash, case variation, unrelated paths and absent command lines.

Fresh corrected campaign: `express-sweep-20260930-pfix`, local bundle parent
`C:/BootOptimBench/artifacts/express-sweep-20260930-pfix`, remote result root
`C:/BootOptimBench/express-sweep-20260930-pfix/results`. Packaged JAR is unchanged;
the fixes only affect script staging/process ownership. Preserve rejected campaigns
and partial evidence. Do not edit a running campaign's JAR, plan or helpers.

Additional harness-only failures: `pfix` refused the stale lock before staging;
`live` started Prism but Windows PowerShell 5 could not overwrite the existing
state JSON with Move-Item -Force. Recovery also passed switch parameters
positionally, leaving force false. This campaign is invalid, not a mod/performance
result. Owned Java was stopped with parent/session/path/creation checks. Recovery
then verified exact original cfg/JAR hashes. Atomic File.Replace now uses
[NullString]::Value (PS5 otherwise converts null backup path to an invalid empty
string); all three real Save functions pass 100 consecutive writes locally AND
on the physical laptop. Recovery binds both force switches explicitly by name.
Four controller scenarios and process-path tests also pass. Fresh authorized
campaign `express-sweep-20260930-final` uses the unchanged combined diagnostic JAR
and Oracle21.0.9. Do not assume success until its measuring state is verified.

Final campaign verified after more than 30 seconds: measuring, valid=true, owned JavaPID7004/session8 with fresh recorded creation and expected Oracle21 executable. Full five-mode measurements and restoration are still pending.

## Completed physical sweep — 2026-09-30

`express-sweep-20260930-final` completed all five modes; offline strict evidence
validation PASS (ordered stages, unambiguous two generations, JVM identity/effective
flags, enabled pack state, activation markers). Collected results live at
`C:/BootOptimBench/artifacts/express-sweep-20260930-final/results` including
`checked-summary.json`. All original files restored each mode. Fresh final remote
hash checks match cfg A9744BF7A4C5660F6B58A6FE1FE9309A91ECACC2AC2189BF099DD2A145AA1ECC
and JAR379BC509EFD43A0D2EDF7CFB6BC1E2F99DD1601B3D8E3AB43B00C70F6DEA4989;
no remaining instance Prism/Java. No world entry or physical in-world F3+T.

| Mode | JVM uptime to presented menu (s) | Repeat menu reload request to future (s) | Whole measured process GC (s) |
| --- | ---: | ---: | ---: |
| control | 457.295 | 389.864 | 67.833 |
| decocraft-v2 | 464.291 | 443.269 | 70.733 |
| ferrite-capacity | 437.722 | 336.608 | 74.851 |
| sodium-axis | 409.624 | 432.589 | 88.603 |
| layer-delta | 420.765 | 378.808 | 62.881 |

Origin is JVM uptime, endpoint is main menu presented after initial reload; repeat
origin is one stock menu reload invocation, endpoint is its completed future.
Launcher/controller staging excluded. Total campaign took ~93.6 minutes, not the
initial speculative 20; actual HDD pack startup plus repeat reload dominate.
Single fixed-order run per mode with uncontrolled HDD/page cache and no interleaved
controls: these raw deltas are exploratory, not causal gains/regressions. Sodium
startup is lower yet repeat reload higher; layer arithmetic cannot credibly explain
36.5 s alone. Ferrite's -53.3 s repeat direction warrants paired physical confirmation
but does not establish a win; lifetime GC includes both generations and is not a
reload-only budget. Decocraft counters are zero because per-call profiling was OFF,
not because target necessarily had zero calls. Do not promote any candidate from
these five timings. Keep all four gates open and carry this evidence into decisions.

## Combined physical comparison requested — 2026-09-30

User explicitly requests accumulating the four candidates and testing whether gains
stack on laptop. Added controller plan mode `combined-abba`: control-1, combined-1,
combined-2, control-2. Same already-hosted composition JAR (SHA unchanged), Java21.0.9,
parent direct baker ON both sides, four candidate switches all OFF/all ON, one menu
reload per process, no world entry. Two pairs reverse order; cache still uncontrolled,
so small/noisy results remain inconclusive. Effective flag matrix and all three
available target activation markers must validate in BOTH combined processes.
No new mod/optimization code or profiling overhead. Offline five controller cases,
six result-checker tests and PS5 atomic state tests PASS including wrong combined
flag rejection. Original transaction restoration and fail-fast policy unchanged.

Campaign express-combined-20260930; local C:/BootOptimBench/artifacts/express-combined-20260930,
remote C:/BootOptimBench/express-combined-20260930/results. User authorized launch;
verify measuring state before reporting live and collect all four endpoints before
claiming accumulation. Prior individual raw deltas must not be summed as predicted
saving. Lifetime GC is not a per-reload budget. Previous sweep took93.6min for5 modes;
this four-process startup+repeat campaign may take ~70–90min rather than20min.

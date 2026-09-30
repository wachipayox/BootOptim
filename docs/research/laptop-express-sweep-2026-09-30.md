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
Next: corrected hosted control/all-enabled smoke with the automatic menu-repeat
endpoint, then the user's signal before touching the laptop. Do not call the full remote
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
The corrected hosted cycle is pending; do not launch the laptop from the old green
status. No laptop contact occurred during this review.

Local bundle: `C:\BootOptimBench\artifacts\express-sweep-20260930\bundle`.
Packaged JAR SHA256:
`3C986500743BA83F30F165D1EC84BCDD5D38D75586BD91CEA63BB9E5D62120AF`.
Source and gates also live in the [persistent candidate register](express-candidates-laptop-register-2026-09-30.md).

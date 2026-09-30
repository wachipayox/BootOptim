# Model preparation: current-call grouping attribution — 2026-09-30

Status: ACTIVE diagnostic; no optimization or performance claim yet.
Base refreshed and inspected: integration `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.
No changes to the laptop sweep's packaged JAR or the user's PC instance.

## Premise and exclusions

The goal remains shortening actual F3+T preparation before the delayed final
freeze by reducing operations first. Existing fast-PC multifocus recording
`rrls-manual-1790624230482.jfr` spans a constructor lane from request-relative
1.835 to 9.687 seconds (7.852 s), not an exclusive grouping measurement. It has
60 stack-presence samples containing BlockStateModelLoader in that interval;
454 fall in other short-stack categories, 34 dependency enumeration and 22 CIT.
Samples are not milliseconds or potential savings. The five-frame recording
cannot establish the exclusive budget for model grouping.

Historical #51 / model-pipeline-deep reported whole loadAllBlockStates 4.406 s
before indexed matching. #55 already eliminated the broad variants × states scan
and is production. Do not treat that old number as residual grouping cost.
Selector predicate construction was only 35.824 ms historically; exact ModernFix
5.27.14 bytecode caches predicates by StateDefinition. Do not add that cache again.
#295 direct dependency collection was closed after order-confounded paired results.
#36 identity bake caching likewise does not justify reopening generic model caches.
Recent Palladium and final-model identifier audits reject their generic caches.

## Confirmed path and unresolved leverage

BlockStateModelLoader's final per-state loop always invokes the current LoadedModel
key Supplier, after discoveredModelOutput.accept. For multipart, ModelGroupKey.create
tests selectors in order, collects current variants, reads coloring values and creates
the group key. For a simple model it reads coloring values and creates a singleton
key. Group-map hashing/insertion is separate work after Supplier.get returns.

A prospective direct loop/empty-color fast path could remove temporary streams and
allocations while retaining each current predicate/getVariant/getValue call and its
order. A broader indexed multipart design requires a proven pure condition domain
and handling mod hooks. Neither should be implemented merely from large state counts.
First distinguish the factory's real budget from grouping-map insertion, identifiers,
JSON parsing, model discovery callbacks and other constructor work.

## Narrow diagnostic and validation gate

`-Dboot_optim.profileModelGrouping=true` is diagnostic-only and default off.
One WrapOperation scopes the existing loadAllBlockStates invocation in ModelBakery.
A second wraps only the current key Supplier.get in the final loader lambda.
Every original operation still runs exactly once and returns the original result.
No selectors, models, getter values or resource data are reused or cached.

Thread-local timing scope is restored/removed in finally even on constructor failure;
group exceptions remain the same exceptions for the stock caller. Nested supplier
calls are counted but only outer intervals contribute wall time, avoiding overlap.
One fixed summary per load reports whole-load wall, factory/group+coloring wall,
the remaining non-overlapping wall, call/nesting/failure counts and thread. This
does NOT time map insertion as part of the factory. Per-call clocks and wrappers
introduce diagnostic overhead, so these numbers cannot establish an end-to-end win.
No JFR or render-thread polling is added. The active physical sweep uses a different
fixed JAR and therefore receives none of this diagnostic instrumentation.

Local packaged build passes. Hosted enabled exact-pack smoke is the next gate;
the runner rejects a requested probe with no successful observed supplier calls.
Do not request another manual PC run unless the hosted attribution demonstrates
enough budget to justify a candidate. Never merge these probes into production.

Standalone tests compile and exercise the real helper: exact result/exception identity, once-only calls, nested intervals, failed-scope cleanup and previous-scope restoration PASS. Runner Python compiles; local packaged build PASS. Hosted probe requires explicit observed available=true evidence, not just a green process exit.

## Hosted attribution result — 2026-09-30

PR303 / commit4beb5490: build, client startup and exact-pack smoke all PASS.
Actions https://github.com/wachipayox/BootOptim/actions/runs/36740130542.
Observed Worker-ResourceReload-1, success=true available=true, 313687 suppliers,
no nested or failed calls. Whole loadAllBlockStates wall4368.127334 ms;
current group+coloring suppliers540.031264 ms; outside suppliers3828.09607 ms.
This factory lane is ~12.36% of the whole measured load (including diagnostic
overhead), and does not include grouping-map insertion. The remainder is the
stronger attribution target; do not claim 4.37 s belongs to group creation or
540 ms is achievable saving. Hosted smoke menu marker86786 ms JVM origin,
zero BootOptim Mixin errors; not a paired performance comparison or physicalF3+T.
Artifacts collected C:/BootOptimBench/analysis-reload-20260930/grouping-36740130542.
No laptop JAR changed. Next source attribution must split remaining discovery,
state publication/map work and reading before choosing a direct optimization.

## Residual follow-up: exact blockstate work callsites

Continue this active diagnostic PR rather than creating an overlapping profiler.
Refreshed integration still a0b8fdc0. Reviewed historical #51/#55 production index,
#217/#221 constructor attribution and its CIT correction, #261 architecture NO-GO,
#262 logging postmortem and closed #295 dependency experiment. #221's CIT constructor
residual is outside our loadAllBlockStates scope and is NOT this 3.828 s remainder.
Production indexed variant matching is present in actual integration source.

New default-off `boot_optim.profileBlockStateWork=true` enables the same existing
loadAll scope plus exact-call buckets: stateToModelLocation (lambda$2), LoadedJson.parse,
discoveredModelOutput BiConsumer.accept (lambda$10), state publication Map.forEach,
and group-map finalization Map.forEach. Actual clean1.21.1 bytecode has nine Map.forEach
calls in loadBlockStateDefinitions: variant matching ordinal0; publication1/3/5/7
and finalization2/4/6/8 are the four compiler-emitted finally copies (normal and
exception paths). Observe every copy, leave original operation/exception in place.

Phase clocks subtract measured nested intervals using the scope's cumulative known
wall. Publication excludes discovery and factory time; six exclusive buckets plus
unmeasured remainder reconstruct whole scoped load. Discovery still INCLUDES its
un-instrumented dependency calls (a possible owner requiring further attribution),
parse includes actual definition/selector work, and remainder includes variant
application, map setup/merge, classloading and wrapper overhead. No sum of inclusive
listeners, no claimed CPU savings. No persistent cache, skipped callbacks, threading,
GL, loader ordering or resource publication changes. Scopes clear/restore in finally.

Local packaged build PASS. Real helper tests verify exact result/exception identity,
once-only calls, nested non-overlapping accounting, nonnegative buckets and failed
phase cleanup. Python gate compiles. Hosted runner now rejects requested work probe
unless all required callsites are observed (`success=true available=true`). Runtime
validation is pending; require this gate before choosing an optimization. No user
PC installation and no laptop bundle modification: combined ABBA campaign remains
separate with fixed JAR/flags. Do not infer savings from counts or diagnostic wall.

## Confirmed exclusive work profile — 2026-09-30

Exact-pack36757251578 /head765ed9aa PASS build, minimalstartup, actualpacksmoke.
BootOptimMixinerrors0, atlas8192x8192x2, JVM-origin main-menu marker89835ms.
Work marker success=true available=true; buckets sum to4710.351387 ms:

| Current-call bucket | Exclusive wall ms | Observations |
| --- | ---: | ---: |
| Identifier/state location | 859.073155 |313687|
| Definition parse |451.072113|11187|
| Discovery/registration callback |1675.237816|313687|
| Group factories |548.194971|313687|
| Publication excluding measured children |350.962401|11076|
| Group finalization |153.855446|11076|
| Unmeasured remainder |671.955485|includes instrumentation/setup|

Location/discovery/group counts exactly match313687; publication/finalization counts
match11076. Diagnostic markers/callsites therefore observed coherently. Legacy
inclusive factory interval573.653739 ms differs from exclusive548.194971 because it
includes added measurement/wrapper overhead; do not treat cross-run differences as
performance regressions. This was a hosted initial reload, not physical F3+T; no
candidate or end-to-end improvement was tested. Per-call clocks/wrappers add overhead.
Evidence C:/BootOptimBench/analysis-reload-20260930/blockstate-work-36757251578.
Cancelled36757026976 lacked finalmixininregistration, excluded from measurements.

The strongest measured remaining owner is discovery callback (~1.675s), which includes
model registration and recursive current-call dependency work; not wholly dependency
enumeration. Next investigation should split this owner at source and seek reduced
operations preserving callbacks before introducing reuse. Closed#295 direct dependency
collection remains order-confounded; this does not authorize repeating its unchanged
candidate. Identifier lane~0.859s is secondary: vanilla already uses direct StringBuilder,
Palladium mutableproperty cache remains unsafe/unsupported. Do not blindly cache
identifiers or skip serializers. All diagnostic code stays out of production/laptop.


## Discovery owner continuation � 2026-09-30

Continue PR303's existing diagnostic and scope; no second overlapping profiler.
The actual NeoForge-patched1.21.1 source shows `registerModelAndLoadDependencies`
calling `model.getDependencies()`, `getModel` for each current returned location,
and `registerModel` (topLevelModels.put). `getModel` cache hits currently do
containsKey plus get; misses include loading-stack traversal and current model
loader hooks. That double lookup is a hypothesis, not yet an attribution or a
safe permission to bypass callbacks. No dependency collection reuse or loader
reordering was implemented; closed#295 remains excluded as an unchanged premise.

Default-off `boot_optim.profileDiscoveryWork=true` enables the same previous
loadAll scope and adds three exact invocation wrappers inside the discovery
callback: dependency enumeration, full current `getModel`, top-level registration.
Each original call/result/exception is preserved. New sub-buckets are exclusive;
the legacy DISCOVERY report reaggregates its sub-buckets so the six original
buckets plus remainder still reconstruct whole loadAll wall. A separate fixed
BOOTOPTIM_DISCOVERY_WORK record reports the split and residual loop/callback time.
Counters outside the loadAll scope are not attributed. No clocks enter the
physical candidate decision JAR from PR304.

Local packaged build and actual helper tests PASS: once-only result/exception
identity, nested exclusive accounting, sub-buckets reconstructing their parent,
failed-scope cleanup and registration. Hosted runtime must observe all three
callsites success=true available=true before choosing a concrete optimization.
Per-call diagnostic wall includes instrumentation overhead and is not CPU or
end-to-end saving. PR304's within-JVM trial runs independently while this analysis
progresses. User explicitly requested this concurrent local investigation.


## Confirmed discovery owner result � 2026-09-30

Source7148c749 exact-pack workflow_dispatch36773873263 PASS. All new callsites
observed available=true/success=true on Worker-ResourceReload-2; zero BootOptim
Mixin failures, atlas8192x8192x2, JVM-origin menu93167ms. This is attribution,
not a performance comparison. Whole loadAll4881.438419ms, reaggregated discovery
1969.485034ms splits exactly into dependencies1293.108099ms (313687 calls),
getModel203.574963ms (893408 calls), registration156.810262ms (313687 calls),
callback residual315.991710ms. Counts of dependency/registration/discovery match.

The two-map-lookup getModel hypothesis now has at most ~204ms inclusive budget,
not a multi-second owner. Prioritize dependency enumeration, ~66% of callback
wall in this instrumented run, rather than introducing a broad cache or
bypassing ModelBakery hooks. #295's unchanged direct multipart loop is still
not a new optimization: it already removed per-selector temporary sets and
streams, and its hosted paired order effect failed to prove a win. New possible
premises must distinguish current callback/collection costs by exact model
type and prove which operations can be reduced without retaining mutable data
or skipping subclass/hooks. A generation-scoped immutable topology plan is
architecturally distinct but requires a callback/mutation contract first;
no implementation is justified yet. ModernFix5.27.14 dynamic_resources mixins
are present in the installed JAR but actual pack config lists only defaultfalse
comments: their dynamic loading locks are not evidence of the active path.

Artifacts: C:/BootOptimBench/analysis-reload-20260930/discovery-36773873263.
CPU savings and physicalF3+T budget remain unmeasured; clocks/wrappers add cost.

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

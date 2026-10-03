# Model ancestry and current material owner — 2026-10-03

Status ACTIVE diagnostic, NEVER production. Fresh integration058ac544aef11c0c10dc32e0aeacba2e39176d40
includes strict path promotion335. No game instance/laptop or existing production optimization changed.
User asks deep P2 investigation, direct operation reduction before scheduling/defer.

## Required history audit and correction to broad novelty

Read integration AGENTS/README/research index/catalog, model-pipeline,
model-pipeline-deep, modelmanager-post56-residuals, web-report-disposition,
resource-resolution-owner, physical operational ledger and open/closed PR bodies,
comments and results13/14/36/57/66/152/160/170/171/177/181/196/248/295/303.
Do not call inheritance flattening or per-model material cache unexplored.
#13 measured parents611ms/327029models and item registration127ms/12283items,
not current multi-second ownership. #66 even unsafe traversal ceiling slower102.394
vs91.267ms; #152+22.825sTTMM, #170+2.097s, #171+9.236s under older whole-startup
criterion. These are historical gates, not proof of zero current physical CPU.
#196 strict IR53,256matches/zeroMismatch semantic proof, but StageB failed:
planBuild77.605msCPU + candidate1195.617 vs stock1589.575, extra~286.9MiB allocation;
non-FaceBakery983.068ms task-sum ceiling NOT ModelManager critical path.
#181 closes same-reload structural plan, #248 generic prepare/commit NO-GO;
changing representation after stock parse/resolve merely adds compilation.
#177 temporary material chain list pool is an existing OPEN unpromoted experiment,
not forgotten as novel optimization; no list-pool candidate implemented here.
#303 already measured MultiPart dependency owner~1066.965ms of1132.376ms dependencies;
#295 direct dependency collect and #305validatedUnion/#313 owner gates retired.
No dependency-set/result cache, geometry flattening or generic executor revived.
No opaque immutable BlockModel assumption: parent/texture/override/customData mutable;
NeoForge RegisterAdditional precedes parent linking, liveBakery exposed at later events.
boot-pipeline-program-2026-09-08.md is still absent on currentintegration; don't invent it.

## Exact-source observations and changed diagnostic premise

NeoForge21.1.248 patched BlockModel source:
resolveParents allocates LinkedHashSet even if parent already linked, then executes
customGeometry.resolveParents and overrides.resolveParents on every call. Therefore
alreadyLinked is NOT permission to skip entire method. getDependencies constructs
fresh current Set from overrides+parent; custom implementation/iteration observable.
getMaterial strips one leading#, creates ArrayList before firstfindTextureEntry,
follows Either.right references with current-list cycle checks; every findTextureEntry
walks current parent textureMaps and synthesizes missing Material on miss. getRootModel,
getElements and getTransform recurse through current parents; transformations create
fresh mutable-visible objects. No second lookup/replay on live maps for counters.

Old inclusive totals and repeat counts cannot budget a new safe operation. This
probe separates current owners with CPU/allocation samples AND counts of original
Map.get/List.contains operations, rather than another structural-plan verifier.
It doesn't change return values or remove methods/callbacks/exceptions.

## Measurement contract

Property -Dboot_optim.profileModelAncestry=true defaultOFF, plus profileStartup=true
and benchmark.exitOnTitle=true required for main-menu report. All7 methods always
invoke current original body: PARENTS,DEPENDENCIES,MATERIAL,TEXTURE_ENTRY,ELEMENTS,
ROOT,TRANSFORMS. Original Map.get supplies probe+hit counters, original List.contains
supplies alias-check counters. No extra map reads or content/Resource/open hooks.
ParentLinked is field observation only, NOT whole resolved/custom/override predicate.
MATERIAL direct label means no alias-cyclecheck; failures separately counted.

Phase bake means within same-thread ModelBakery.bakeModels lexical scope with finally
restoration. outside_bake includes constructor/pre-bake AND other startup callers;
DO NOT call it exclusive constructor wall or an exhaustive cross-thread phase.
Stock bake current path synchronous; newly detached custom-thread callbacks are not
inherited by this lexical ThreadLocal, so out-of-scope attribution is explicit.
Each row counts recursive calls but suppresses sampling nested same-kind intervals.
Different kinds may nest/overlap; NEVER SUM sampled CPU/wall rows as exclusive phases.
Whitened expected1/256 sample, not ordinal periodic sampling. Full method-call counts
but only sampled CPU/wall/allocated-bytes sums; DO NOT multiply256 as exact cost,
extrapolate savings, compare diagnosticstartup to production TTMM, or declare cheap
based on sparse/zero/quantized samples. Original bodies include descendant observer
cost; sampler start/end has different instrumentation boundaries. Per-thread pooled
arrays avoid oneFrame/object percall; max256stack depth, overflow invalidates.
14fixed rows, no capturedstrings/models/sprites/resource identities retained.
CPU and allocation availability+successful sample counts reported separately;
current-thread allocations include instrumented descendant operations, not pure model
allocation. ProcessGC/wall not inferred from thread-allocation counts.

Report after one main_menu before automatic exit, inflight0/overflowfalse required.
Parser requires14unique phase/kindrows, all sampledCPU/alloc available, no failures,
materialdirect+aliased=calls, probehit<=probes, parentlinked<=calls, coreowners active
with nonzeroCPU and alias+map hooks observed. Missing hook/zero samples invalid.
All14rows aren't required nonzero because absence in a lexical phase is expected.
Actualhelper contract verifies phase routing, nestedcounter/samples, map/alias counts,
throwing-path cleanup and zero inflight. Hosted gate:14orderedpacks/oneinitialreload,
8192x8192x2 blockatlas, no new mixin/reload errors and mainmenu. Build is necessary,
not proof Mixins hooked. No physical run requested before populated hosted result.

## Decision gates after this run

1. Inspect valid actual instrumentation/CPU/alloc coverage and method distributions.
2. Large direct-material share + material allocation ownership may justify a NEW
   delay-allocation or simpler cycle-detection operation preserving original calls,
   not #152per-model cache/#177pool without its prior evidence/new cost.
3. AlreadyLinked parent counts need custom/override residual attribution before any
   cycle-set-only optimization; never skip callbacks or parent's model closure.
4. Parent/element/root recursion that is cheap -> document/retire same-premise work;
   architecture only reopened with actual different immutable domain/cost proof.
5. Before real candidate, exact semantics/callback/mutation/null/cycle/error contract,
   actual operation C/B/B/C netCPU twoC/twoB, clean hosted and physical sensitive gate.
This probe is evidence gathering, not a performance or promotion claim.


## Additional recovered direct-versus-reference evidence

#57 comment5481677474 records2,203,625materialcalls with1,992,105direct-local
(90.40%) and211,520complex,179,349hits/32,171misses even after excludingdirect
calls from the memo. Selectivecache still regressed bakeModels9757.533vs8129.116ms
and Elements3757.312vs3110.528ms. This supports measuring current temporary-list
allocation on direct resolution, NOT another complex-only cache or count-basedclaim.
New probe is stock operation-only: candidate bytecode/algorithm not yet implemented.

Local gradlewbuild--no-daemon PASS including actualhelper phase/recursion/probe/
throwingcleanup contract; sixparser tests PASS(inactiveprobe, missingrow, unavailable
allocation, unfinishedclosure, malformedmaterial accounting). Existing strict-path
production ASMcontract alsoPASS. Packaged bootstrap remains distributable target.
Next hosted gate must validate actual method/operation hooks, not build alone.

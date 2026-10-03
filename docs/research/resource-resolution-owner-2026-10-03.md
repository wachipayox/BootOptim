# Point-resource resolution distribution — 2026-10-03

Status: diagnostic only, never merge profiling into production. Base7138fcb3;
rebased onto documentation-only d5a61c34 after lexical #331 was retired.

## New premise and history

#69 profiled list/get operations only inside known model/atlas contexts, with
namespace-inclusive timings. #71 measured reader/open/read stages and broke
legitimate null-source shader Resources; this diagnostic never touches Resource,
source, supplier bodies, streams or readers. #182/#326/#329 covered ZIP
listResources enumeration, not point-resource provider search. #318/#330/#331
covered only first lexical prefix creation, not provider lookup/existence.
No broad resource cache, immutable-generation assumption or callback suppression
is justified by these prior results. #303 dependency scope is another owner.

FallbackResourceManager's stock point lookup scans packs in reverse priority,
asks each provider for a supplier, checks filter-only entries and creates the
Resource plus lazy metadata finder on a hit. Stack lookup scans suppliers,
metadata filters and preserves reverse order. Provider callbacks may observe
current contents; manager identity does NOT make results immutable. A lazy
metadata supplier may perform additional provider lookups AFTER the measured
public method, so this boundary does not claim all PathPackResources calls.

## Instrumentation contract

Default-off `boot_optim.profileResourceResolution=true`. Wrap both exact public
getResource/getResourceStack methods, always call originals and return original
Optional/List object, with finally scope restoration on exceptions. Wrap only
the stock direct PackResources.getResource call; original callback executes
once with the same receiver and arguments. No packId method call; class name
attribution only. No synthetic Resource, source dereference, file test, read,
cache, scheduler or GL mutation. Null original returns remain null.

Count (actual manager identity, ResourceLocation, point/stack mode) up to200000
keys. Counters for all direct provider calls report class, hits/nulls/failures.
One in16 ROOT requests gets CPU/wall clocks. Nested requests have counters but
no nested timing, so sampled root and provider sums don't double-time nested
provider scopes. Provider samples are inclusive of their original callbacks;
NOT exclusive IO or recoverable CPU. CPU collection is not forcibly enabled.
Snapshot after first main_menu marker; record inflight and truncation explicitly.
Retained manager identity keys are discarded on quiescent report.

Sampled CPU is ACTUALLY observed only in the selected requests; do not multiply
by16 and call it exact total CPU or savings. Deterministic sampling may correlate
with query order. Root CPU includes timer/counter costs within the original
call and is diagnostic-contaminated; root-minus-provider is not a clean tiny
exclusive resolver budget. Thread CPU quantization, unavailable samples, active
scopes or truncation invalidate a small-cost closure. Count distribution and
large sampled provider ownership choose the next bounded source audit.
Diagnostic startup timing and retained maps are NEVER performance A/B votes.

## Gates and next decision

Local packaged build PASS. Hosted exact-pack smoke must populate root queries,
unique/repeats, sampled CPU coverage and provider rows; no new failure, packs
selection and atlas8192x8192x2 unchanged, main menu reached. A zero hook or
failed/unavailable/truncated/inflight snapshot is not evidence of cheap work.

Then inspect repeat/depth/hit/provider distributions BEFORE considering a
resolution plan. A hit/miss plan must preserve current filters/provider effects;
no optimization is currently implemented. Material provider cost can justify a
narrow deeper owner diagnostic; small hosted data cannot close the physical
CPU/HDD-sensitive front alone. No laptop or manual instance run launched here.
## First hosted result and bounded detail follow-up

Run37128621786@458c963e passes: selected14packs in order, blockatlas8192x8192x2,
Mixin0, menu88137ms (diagnostic health only). Snapshot49367rootqueries,
11218uniquekeys,38149repeats,29363hits,failures0,inflight0,truncatedfalse.
All3086sampledroots haveCPU coverage. Actual sampled root CPU500.558225ms,
providerCPU351.679026ms, not extrapolated to exact total or savings.
Direct provider calls1445071 (~29.27 per root request):

| Provider | Calls | Hits | Sampled original CPU ms |
|---|---:|---:|---:|
| PathPackResources | 984244 | 805 | 253.978788 |
| FilePackResources | 378245 | 12055 | 44.479642 |
| CompositePackResources | 47520 | 485 | 7.509775 |
| VanillaPackResources | 35048 | 16049 | 45.637533 |
| LDLib CustomResourcePack | 14 | 0 | 0.073288 |

Path provider misses dominate the call distribution, but these observations do
NOT authorize skipping providers/callbacks or storing negative results. The
exact keys, point/stack mode, query families and repeated-vs-first ownership
were missing, so extend THIS diagnostic with family count/hit/probe/sample rows
and top40 frequent key rows. Retain current instrumentation/return contracts;
per-key count is atomic to identify repeat starts under concurrency. Family
keys are namespace/firstpathcomponent/mode, bounded2000; all detail counters
run outside sampled CPU endpoints where possible. Remaining root instrumentation
contamination still applies. Top keys keep manager identities distinct and are
not a captured full ordered query workload. No candidate or physical run yet.

Raw first artifact: C:/BootOptimBench/analysis-reload-20261003/resource-resolution-37128621786.
The later build must populate detail rows with matching global/family accounting
before selecting an actual optimization mechanism.

## Detail gate and sampling repair

Detail37130137711@379a0fd9 PASS accounting (global/family/probes/CPU partition),
49366roots11218unique38148repeats; selectedpacks/atlas/Mixin health unchanged.
Models family15840calls15824repeats15840hits617760probes: just16distinct keys,
39providers perlookup. minecraft root family7922calls/7896repeats/2hits and
textures8187calls/8041repeats/265hits also dominate repeated probing. Topkeys
include16armor item JSONs (netherite1152each, others936each) and missing
r_layer_1.png/d_layer_1.png/helmet.png and similar locations. This resembles
CIT paths but count alone is NOT caller attribution or permission to revive
#257's rejected lifecycle caching. Existing base-model bridge deliberately
calls manager.getResource BEFORE its parse/open cache; public query observations
must still be preserved until a safe source alternative is established.

Hard sampling caveat: old ordinal&15 selected all288helmet.png queries but
zero of several equally repeated keys. Counts are valid, family/CPU totals are
raw actual observed scopes, but representativeness is disproven. Do NOT use
480.708ms sampledCPU or family rankings as projected total/optimization budget.
Repair selection using a SplitMix64-whitened sequence before the CPU endpoint;
expected1/16root timing, independent of the observed16-call cycle. Add optional
profileResourceResolutionCallers: expected1/256root StackWalker traces outside
CPU endpoints, bounded200distinct traces/top40output, to attribute actual
callers instead of assuming all armor/lookups belong to CIT. No extra resource
operation, callback skipping, caching, scheduling or game behavior change.
No hardware comparison is requested until repaired detail/caller gate passes.

Source anti-trap: SecureJarHandler3.0.8 already overrides the JDK provider exists
method to call UnionFileSystem.exists directly. A proposed bypass of checkAccess
exception creation is already present and is NOT a new optimization. UnionFS
still applies its filter to candidate paths, even on misses, and may obtain
attributes before that callback. A manager-generation negative-result cache
would suppress those observations and can hide directory content changes.
FileUtil.decomposePath returns a mutable ArrayList for multi-segment paths;
sharing its DataResult/list globally would also change consumer semantics.

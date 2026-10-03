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
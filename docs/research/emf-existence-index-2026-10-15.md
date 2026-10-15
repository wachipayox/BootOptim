# EMF absent-resource probes answered from EMF's own listing — 2026-10-15

Status: **ACTIVE** — hosted A/B done (mechanism proven, effect below hosted resolution); physical-laptop gate or an explicit user decision pending.

Origin of the idea: the private RRLS fork's reload experiments (September 2026, archived in [`rrls-fancymenu-early-menu-2026-09-24.md`](../archive/research/rrls-fancymenu-early-menu-2026-09-24.md), sections 2026-09-27 "complete existence index" and "guarded complete-index physical activation"). That work was measured on the fast PC inside RRLS's post-menu reload and manual F3+T reloads, not on `main_menu`. This entry re-asks the question for BootOptim's endpoint.

## Why this is not a repeat

The `EntityRenderDispatcher` slot is a serial, CPU-bound apply slot: 2153 ms exclusive wall hosted (#184/#190), 96 % of it in `createEntityRenderers` ([lifecycle-split audit](../archive/research/entity-renderer-lifecycle-split-2026-09-08.md)). The ledger closed **moving, deferring or parallelising** that work (#94, #95, #190, #194; ASM-template cache #96, ceiling ~58 ms). It never tried to **remove redundant work inside** the provider loop. This candidate changes no ordering, thread, lifecycle or output; it removes lookups whose answer is already known.

## Mechanism

EMF lists `emf/cem` and `optifine/cem` once per reload (`ResourceManager.listResources(prefix, always-true)`), then for every candidate `.jem`/`.properties` that is not in the listing still calls `ResourceManager.getResource`, which walks the full pack stack and misses. In the RRLS trial the entity scope made ~5100 such calls.

If the listing is provably complete, a path below the prefix that is not in it cannot exist, so BootOptim answers `Optional.empty()` without the pack walk. Completeness conditions, all checked at run time:

1. the predicate passed to `listResources` accepts everything (probed with three shapes of location); otherwise the prefix is not indexed;
2. every pack in `listPacks()` is static: it exposes no client namespace, or `getResource`/`listResources`/`getNamespaces` are inherited unchanged from `PathPackResources`, `FilePackResources` or `VanillaPackResources`; `CompositePackResources` is checked recursively. One unknown pack leaves the whole manager on stock and logs FALLBACK with the pack classes;
3. the snapshot belongs to the current EMF cache generation (`clearCache` invalidates it; an enumeration that started before a clear is discarded).

Compared with the RRLS version this removes the exact-version gate (`3.2.4`) and the pack-class allow list by name, including the SHA-256 pin of the Fabric placeholder class, in favour of the behavioural checks above (user decision 2026-10-14).

Differential guard: the first 64 index answers and then one in 64 are re-checked against the stock `getResource`. A single disagreement disables the mechanism for the session and logs `[WBOMBO-COMPAT] FALLBACK ... reason=differential_mismatch`. The same samples time the stock probe, so the run reports its own estimate of wall avoided (`estimated stock wall avoided`; the first probes are cold, so it overestimates).

Switch: `-Dboot_optim.emfExistenceIndex=false` restores stock. Injection failures are reported by `CompatInjectionCheck` (EMF package added to its mod map).

## Evidence carried over (not BootOptim's own)

| Item | Value | Origin / boundary |
| --- | --- | --- |
| Directory-existence calls, entity scope | 5100 (+384 player) | RRLS trial, fast PC, EMF 3.2.4 |
| Exclusive wall of those calls, stock | 173–191 ms | same, serial same-PC trials |
| With complete index | 3–5 ms | same, 3799 absent queries avoided |
| Visual | animations correct in a later run | user, same PC; not a controlled equivalence proof |

Not carried over: any claim about `main_menu`. The RRLS runs are not paired A/B, and they ran inside RRLS's hidden post-menu reload.

## Gate

1. Build and Startup CI.
2. Hosted exact-pack smoke, `exact-pack-fixture: latest`: expect `[WBOMBO-COMPAT] ACTIVE ... mechanism=emf-existence-index`, the `BootOptim EMF existence index` line with its counters, and 0 new FALLBACK. If the current Beta pack no longer carries EMF, or a pack provider is unsupported, the run says so and the premise is closed as not applicable.
3. Hosted A/B, 3 repetitions, same fixture revision for control and candidate; the revision and `manifest_sha256` are quoted from the job summary. Judge the `EntityRenderDispatcher` slot first, then `main_menu`.
4. A saving of ~0.15–0.2 s is below hosted run-to-run noise (runner spread 35 % in [beta-rev3-baseline](beta-rev3-baseline-2026-10-15.md)). A coherent segment effect with ACTIVE and no FALLBACK is enough to continue to the physical laptop; hosted `main_menu` alone cannot accept or reject it.

## Hosted result (PR #363, run 38800697103, 3 candidate + 3 control, fixture `latest`)

Origin: hosted exact-pack surrogate, fresh VM per run, endpoint `main_menu`. Fixture revision and `manifest_sha256`: see the job summary of that run.

- Mechanism: `[WBOMBO-COMPAT] ACTIVE mechanism=emf-existence-index mod=entity_model_features@3.2.4` in 3/3 candidates; `wbombo_compat_fallbacks` empty in 6/6; 0 mixin errors; the pack's provider inventory was accepted (no unsupported-pack FALLBACK). Differential guard: 109 stock re-checks in each run, 0 disagreements.
- Probes answered: 3000+ per run (2891 served + 109 verified at the 3000 mark).
- Segment estimate from the guard's own timing (stock absent probe 23–67 µs, cold-biased): **110–195 ms** of stock wall avoided per run. This is an estimate of the removed lookups, not a measured slot delta; the hosted CI does not report the `EntityRenderDispatcher` slot.
- `main_menu` medians: candidate 64,239 ms vs control 82,936 ms (−22.5 %). **Not attributable**: `mod_entrypoint` (before this mechanism runs) is also −21 % (24.8 s vs 31.5 s), and runs split into two clusters (~64 s: candidate 1, 3 and control 3 at 68 s; ~83 s: candidate 2, control 1, 2). Runner variance, as already recorded in [beta-rev3-baseline](beta-rev3-baseline-2026-10-15.md). A ~0.15 s effect cannot be seen at this spread.

Disposition: the premise holds (EMF 3.2.4 present, ~3000 redundant probes, no semantic disagreement) but the saving is ~0.1–0.2 s, below what hosted A/B can resolve. Decide by the segment-first policy: keep if the user accepts a verified, guarded, low-risk CPU saving in its segment; otherwise run the laptop gate (HDD cost of absent probes may be larger) before integration. Not rejected.

## Residual risks

- A pack that adds EMF resources after the listing within one generation would be answered absent. Static packs are required; the sampled guard is a tripwire, not a proof.
- Hosted CI is a software surrogate on Linux/llvmpipe; the HDD laptop may weigh absent probes differently (page-cache dependent).

## Reopening / rejection criteria

Reject if the hosted segment shows no movement across two controls and two candidates, if the pack carries no EMF resources worth indexing, or if the guard reports a mismatch. Reopen only with a changed premise (different EMF lookup structure, or a pack with far more `cem` candidates).

## Related fronts from the same RRLS history, not implemented here

Each needs its own premise check and entry before any code: EMF expression-parser traversal (−164/−177 ms exclusive on the same scope), EMF prepared animation text (−109 ms on `jem-prepare`), CreateFood rotated-shape memo inside MoreCulling's shape listener (listener 884 → 460 ms, mixed with other changes).

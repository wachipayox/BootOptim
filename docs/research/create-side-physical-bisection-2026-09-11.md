# Create-retaining physical-side bisection — 2026-09-11

Status: **PROFILED / diagnostic only**

PR: #252. Prerequisite tooling/evidence: #251. Source-level causal evidence: #238 and #246.

## Question

PR #251 established an artifact-safe first bisection and selected its `complement-1` side for refinement because that side retains the physical Create/Flywheel/Ponder/Bits'n'Bobs family. The next question was whether that 57-JAR retained workload could be narrowed further without splitting top-level JARs or runtime/compatibility families, and without treating reduced-pack timing differences as additive mod costs.

## Design

The accepted #251 `complement-1` scope is pinned by source pack fingerprint `6e9b250390ee2cbeb5e4f0ad3363d5159a51481dbfe1412f815882fc5d4ab990` and by SHA-256 for all 57 selected top-level JARs. The branch-scoped planner rejects a changed fingerprint/hash or any compatibility unit that crosses the parent boundary.

Within that exact scope, physical compatibility units are classified by whether their required dependency closure reaches `create`. This produces a 12-artifact Create-connected assignment and a 45-artifact residual assignment. `create/flywheel/ponder/bits_n_bobs` and `tfmg/we_companion` stay indivisible; the same render/menu/observable compatibility declarations and Create runtime-symbol provider rule from #251 remain active.

The assignments are disjoint: intersection 0, union 57. Effective exclusions are tracked separately. Across the two sub-complements, effective exclusion overlap is 104 artifacts: 103 are inherited from the #251 parent closure and exactly one (`CreateNowheel`) is a new subscope closure effect. It is not a shared physical assignment.

## Accepted hosted evidence

Run: https://github.com/wachipayox/BootOptim/actions/runs/34550549871 at diagnostic head `cb1f9af34a3bc869e2e93fdf6182f3f32f09d621`. Build run `34550549845` is green.

Origin: hosted exact-pack, Ubuntu/Xvfb/llvmpipe, Oracle JDK 25.0.4, `-XX:ActiveProcessorCount=4`. Endpoint: `main_menu`. Every interpreted row reached that endpoint with `resource_contract_valid=true`, exact ordered resources 14/14, `reload_count=1`, and `bootoptim_mixin_errors=0`.

| variant | selected physical JARs | TTMM | mod entrypoint | post-entrypoint |
| --- | ---: | ---: | ---: | ---: |
| `scope-full` | 57 | 37.554 s | 15.075 s | 22.479 s |
| `subscope-retain-create` | 11 | 34.135 s | 14.366 s | 19.769 s |
| `subscope-without-create` | 45 | 18.266 s | 9.690 s | 8.576 s |

These are single fresh-host diagnostic workload observations. They are not additive, are not estimates of per-mod cost, and do not establish savings or gameplay equivalence. In particular, no subtraction of the rows is valid.

The Create-retaining arm materializes 11 JARs after `CreateNowheel` becomes closure-unrunnable: Create 6.0.10 (co-located Flywheel/Ponder), CreateBitsNBobs, two physical TFMG JARs, `we_companion`, CreateDragonsPlus, CreateCraftsAdditions, CreateDesignNDecor, CreateHypertubes, CreateStuffNAdditions, and Exposure. The residual arm retains all 45 assigned artifacts with no additional closure removal.

Artifacts under run `34550549871`: aggregate `10180707115`, scope-full `10180701849`, retain-create `10180698698`, without-create `10180681507`. Each per-variant artifact contains `result.json`, resource-selection report, schema-2 scaling plan/manifest and logs.

## Decision

Stop arbitrary pack-level splitting on this branch. The next physically coherent attribution target is the 11-JAR Create-connected closure. This is a structural prioritization, not a claim that its 34.135 s belongs to Create; the residual workload remains nonzero and the reduced configurations are not gameplay-equivalent.

Independent causal evidence already provides the correct source boundary inside this closure: #238/#246 place exact Create 6.0.10 `Create.onCtor` on the observed FML construction critical chain. #246 further attributes 388.570 ms of one valid observational replay to the `AllBlockEntityTypes` registration interval and 181.695 ms to packets. The next diagnostic should therefore instrument consecutive `BlockEntityBuilder.register()` exits/source sections in exact `AllBlockEntityTypes.<clinit>` while preserving bytecode execution order, Registrate/FML callbacks, class-initialization/failure order and thread affinity. No runtime optimization is justified yet.

## Semantic and visual risks

Reduced packs are diagnostic workloads only. Passing resource selection 14/14 and reload=1 does not prove full visual/gameplay equivalence. Create/Registrate registration and client callbacks are order-sensitive and not a safe concurrency premise, and class initialization can alter failure order. Hosted llvmpipe is not physical-laptop hardware. Any future source-level candidate must first demonstrate causal critical-path leverage through `main_menu`, then pass semantic/visual gates before an optimization claim.

## Reopening / next gate

Reopen pack-level splitting only if source-level tracing shows the dominant Create-connected wall is actually distributed among several physical addons and cannot be attributed inside Create/FML. Otherwise proceed directly to the version-pinned `AllBlockEntityTypes.<clinit>` diagnostic described above.

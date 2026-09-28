# Direct vanilla multipart dependency collection — 2026-09-28

Status: **ACTIVE EXPERIMENT**, default disabled. This is not production evidence.

## Premise and measured boundary

The physical fast-PC `nooptim` instance ran three complete in-world F3+T reloads with integration BootOptim `a0b8fdc`, a selective FancyMenu fork and RRLS focus diagnostics. The RRLS manual-request futures completed in 19.389, 17.647 and 17.663 s; `ModelBakery` construction reached its milestone at 9.687, 8.080 and 8.199 s from each ModelManager call. These are diagnostic, unpaired process/request measurements, not an A/B speed claim. The complete trace and limitations are in [the RRLS/FancyMenu research PR #289](https://github.com/wachipayox/BootOptim/pull/289), which is not yet integrated.

The probe counted 147,906 exact vanilla `MultiPart.getDependencies` calls per reload on 961 multipart identities, internally making 3,227,304 exact vanilla `MultiVariant.getDependencies` calls on 122,148 variant identities. Stock `MultiPart` creates a temporary dependency set for each selector's `MultiVariant`, then flattens these sets into another set. This repeated allocation/stream work is a narrower premise than the rejected #36 top-level bake-result identity cache: no baked model or parsed model result is reused.

## Candidate and semantics

When `-Dboot_optim.multipartDirectDependencies=true`, this branch collects each exact vanilla selector variant's model locations directly into one **fresh** `HashSet` for the current call. It makes no cache and retains no state between calls or resource reload generations. If the multipart or a variant has a custom runtime subclass, it falls back to the stock method so subclass dependency overrides remain observable. The source lists are read on every call, preserving later mutation visibility. The flag defaults false; `BOOTOPTIM_MULTIPART_DIRECT_DEPENDENCIES status=active` proves the path ran without per-call logging.

The stock return is a `Collectors.toSet()` set; the candidate returns a fresh `HashSet` with the same `ResourceLocation` elements and uniqueness. It does not alter the caller's iteration contract beyond the unspecified set order. A mod Mixin that augments exact vanilla `getDependencies` could still be affected by any cancellable HEAD injection; exact-pack runtime and visual checks are therefore required.

## Gate and disposition

Run compile/package, startup and hosted exact-pack same-branch A/B (three fresh VMs each). Compare identical BootOptim process-origin → main-menu endpoints, ModelManager critical-path markers where present, pack selection, Mixin errors and the active marker. A small hosted delta is only a hypothesis for in-world F3+T; the hosted workflow measures startup, while an in-world paired run with RRLS would be needed before any claim that manual reload became faster. Do not promote solely because call counts are large. Reject if the full startup result is neutral/regressed or correctness cannot be established. No laptop run is justified at this stage.

Historical PRs checked: #13/#14/#35/#36/#47/#55/#257 and the [model pipeline ledger](model-pipeline.md). The exact dependency-loop premise did not have an overlapping active PR as of integration `a0b8fdc`.

## First hosted exact-pack A/B

[Workflow #36474778215](https://github.com/wachipayox/BootOptim/actions/runs/36474778215) completed six fresh-VM runs successfully. Candidate and control each reached the menu with `8192x8192x2` block atlas and zero BootOptim Mixin errors. The candidate emitted its one-time active marker. Candidate process-origin → menu results were **90.050, 68.598, 90.629 s**; controls were **96.464, 68.194, 88.029 s**. Their medians are **90.050 vs 88.029 s**, candidate **+2.021 s**. The corresponding initial reload→FancyMenu medians were **40.853 vs 40.948 s**, candidate **−0.095 s**. Candidate/control per-index menu differences are −6.414, +0.404 and +2.600 s; pre-entrypoint differences are −3.364, +0.057 and +1.756 s, already showing large unrelated runner variance before the target path. The panorama medians also differ by −0.600 s. This is **not a coherent end-to-end win** and gives no basis to promote or claim F3+T improvement.

The mechanism is CPU/allocation-sensitive and the hosted delta is small relative to variance, so this first A/B does not establish a physical no-effect result either. A same-VM alternating-order paired hosted run is the next narrow noise gate. Only a coherent within-pair reload-phase improvement would justify asking for a physical in-world F3+T comparison; otherwise leave the experiment unpromoted and redirect to the larger model preparation/apply bottlenecks.

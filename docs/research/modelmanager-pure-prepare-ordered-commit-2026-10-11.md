# Relay 218 — pure preparation before stock-ordered model commit (2026-10-11)

**Disposition: REJECT / NO NEW RUNTIME CANDIDATE.** This is a current-integration, evidence-based closure of one concrete family, **Decocraft 3.0.11 first/base Blockbench geometry**. It does **not** reject the abstract possibility of pure preparation in future APIs. It rejects the currently available *BootOptim-side* consumption seam. No executor, scheduling change, cache, diagnostic, fork, or candidate switch is introduced.

- Repository authority refreshed twice: `agent/integration-current` at `058ac544aef11c0c10dc32e0aeacba2e39176d40` (2026-10-11 inspection; HEAD still the 2026-10-03 #335 promotion).
- Runtime target: Minecraft 1.21.1 / NeoForge 21.1.248; pinned public exact-pack fixture `exact-pack-2026-09-02-v1`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.
- Scope: pure pre-sprite primitive preparation, with **current-generation material/sprite/ModelState and fresh quad construction in the original stock bake**, followed by the original complete-map NeoForge callbacks and ordered ModelManager apply.
- Evidence type: *historical contract-valid hosted profiles, bytecode inspection, and rejected prototype*, not a new A/B on the current commit. No private laptop, full-pack files, or local machine access was available or assumed.

## Mandatory history and why this is not a new scheduler proposal

Read current `AGENTS.md`, both README catalogs, `exact-pack-ci.md`, `segment-first-decision-policy-2026-10-01.md`, `owner-candidate-final-decisions-2026-10-01.md`, `web-report-disposition-2026-10-02.md`, and `modelmanager-post56-residuals.md`. Checked the current tree: the retained Decocraft quarter-turn and JSON batch paths and vanilla model optimizations are present; the hypothetical generic ModelManager executor/IR is **not** installed. #337 parent lazy-set and #336 parent/texture profiler remain separate active PRs; no code/profiler overlap is proposed.

The applicable open/closed PR chain is [#14](https://github.com/wachipayox/BootOptim/pull/14), [#36](https://github.com/wachipayox/BootOptim/pull/36), [#47](https://github.com/wachipayox/BootOptim/pull/47), [#57](https://github.com/wachipayox/BootOptim/pull/57), [#160](https://github.com/wachipayox/BootOptim/pull/160), [#214](https://github.com/wachipayox/BootOptim/pull/214), [#217](https://github.com/wachipayox/BootOptim/pull/217), [#221](https://github.com/wachipayox/BootOptim/pull/221), [#222](https://github.com/wachipayox/BootOptim/pull/222), [#224](https://github.com/wachipayox/BootOptim/pull/224), [#248](https://github.com/wachipayox/BootOptim/pull/248), [#250](https://github.com/wachipayox/BootOptim/pull/250), [#253](https://github.com/wachipayox/BootOptim/pull/253), [#254](https://github.com/wachipayox/BootOptim/pull/254), [#261](https://github.com/wachipayox/BootOptim/pull/261), and [#265](https://github.com/wachipayox/BootOptim/pull/265), including substantive result comments. #43 side-load, #303/#305/#313 multipart, and the unrelated resource/ancestry lanes are expressly excluded.

- #14's eager top-level parallel bake improved bake 668→500 ms but regressed main-menu 15,406→15,554 ms in its historical same-runner pair.
- #36's 64.57% eligible identity-call reduction moved bake only ~0.413 s, without proven menu gain.
- #248 ruled out a **generic** prepare/barrier/commit split because ModelBakery contains mutable custom-loader/CIT state and complete-map callback/publication barriers.
- #261's proposed narrow semantic-key BakePlan then failed the strict transitive-safety census in #265: `safe_roots=0/334964`, `safe_calls=0/411257`, `safe_exclusive_ms=0`; **no proven-safe closure**. The observed `opaque_exclusive_ms=9640.235` is the profiler's exclusive CPU attribution, not a possible savings total. This does not license relabeling ordinary BlockModel leaves pure merely by their class.

This record does not claim the earlier, unmerged diagnostic branches are integrated. It consolidates their *published observations and decisions* against the current integration source tree.

## Family-level causal and source-contract test

**Concrete target:** the 3,527 first/base Decocraft `BBGeometry` bakes associated with a non-null parsed `BBModel`, not all 4,056 observed constructors. Work after the constructor is the expensive first geometry bake; quarter-turn reuse already handles other orientations. #217's hosted exact-pack result [34293289041](https://github.com/wachipayox/BootOptim/actions/runs/34293289041) attributed **3,227.384 ms** of **exclusive elapsed bake-frame time** to 3,527 first identities. That is a historical local cost envelope, **not** net CPU saved, future parallel slack, or menu improvement.

The DAG is genuinely gated. In [#250's validated hosted profile 34546031071](https://github.com/wachipayox/BootOptim/actions/runs/34546031071) (SHA `0937ae83fcd7cd699d49d71b1bfa7d07861b7f5e`), the first Decocraft constructor returned at **48,642.088 ms** on Worker-ResourceReload-0, block-model source future completed at **52,267.352 ms** (observer Worker-ResourceReload-2), aggregate atlas future at **52,479.293 ms** (Worker-ResourceReload-1), ModelBakery constructor returned at **63,477.661 ms**, and first `BBGeometry.bake` began at **63,498.763 ms** on Worker-ResourceReload-0. Source-set-complete→first-consume was **11,230.757 ms**, and atlas-complete→consume was **11,019.470 ms**. These are **single-run monotonic wall intervals**, not free cores or a predicted saving.

The #250 contract passed: exact **14/14** selected packs in order, one reload, `8192x8192x2` block atlas, 0 BootOptim Mixin errors, `main_menu` reached, and no added/removed normalized ERROR messages relative to the valid #214 reference. Its ModelBakery prepare **11,210.177 ms**, nested bakeModels **8,479.859 ms** inside loadModels **10,001.565 ms**, and stock ordered apply/commit **465.366 ms** belong to overlapping/nested and sequential scopes as labeled: they must **not** be summed as listener savings.

**Pure producer feasibility: YES, only as a conceptual primitive.** [#253's pinned 3.0.11 bytecode inspection](https://github.com/wachipayox/BootOptim/actions/runs/34550461475) (Decocraft binary SHA-256 `b0589eb7d03b13bbf3b9c45df7f50db556a721882e9ad2bb6be0ff23e5a64526`) shows `BBModelGeometryLoader.read` obtains the `BBModel` and passes it into `BBGeometry.<init>`, which stores final fields. A non-null source can exist before its first bake. Do not schedule all 4,056 constructors: 529 lack evidence of the first/base prepare domain.

**Safe, nonduplicating stock consumer feasibility: NO with today's externally accessible seam.** That same bytecode inspection shows private `BlockbenchModel.buildQuads` fuses its ordered `elements → faces.keySet/get → filters` traversal with `BAKERY.bakeQuad` calls. Preparing/flattening early, then letting this method run, materially repeats the traversal. Cancelling or substituting this method to consume the early result suppresses/changes its stock injection/callback and local exception surface. Redirecting iterators/fields changes observable collection/control-flow semantics. #224 already proved that `applyElementRotation` must retain the entire stock method body and side effects; current `ModelState`, material, sprite UV and quads must remain late.

[#222's actual early-IR prototype](https://github.com/wachipayox/BootOptim/pull/222) supplies the decisive counterexample: **3,527** prepares and commits, **964,046** quads, 0 prepare failures/fallbacks, final sprite-local and eight-vertex-lane equivalence, but **prepare 4,133.246 ms + commit 1,558.717 ms on the same worker** and final hosted paired candidate minus control **+3,015 ms reload→FancyMenu** and **+1,907 ms menu** (run [34299359452](https://github.com/wachipayox/BootOptim/actions/runs/34299359452)). These are a historic same-VM **one-pair** observation, not the requested 2C/2B net-CPU acceptance experiment. Equivalence of output **does not** establish all callback/injection/failure equivalence when the containing stock method is replaced.

The proposed direct-mod API that could fix the late seam is also **not currently an available route**: [#254](https://github.com/wachipayox/BootOptim/pull/254) found no reproducibly identified author-controlled Decocraft 3.0.11 source and documented the public **All Rights Reserved** licensing, with no permission to publish a derivative fork. This report does not obtain, copy, reconstruct or redistribute Decocraft source. License/provenance conclusions are as established in #254; any changed author authorization needs fresh verification.

## Binary decision and measurement discipline

**REJECT present BootOptim-side detached prepare → stock ordered commit.** The upstream source availability timing gate *passes*; the indispensable nonduplicating and observable **late-consumer contract fails**. Constructing a performance candidate without repairing that seam would repeat #222/#253, not test a new mechanism. This is a **semantic/architecture NO-GO before A/B**, not a statistically measured zero CPU benefit.

| Question | Evidence status |
| --- | --- |
| Specific gated family? | Yes: Decocraft non-null `BBModel` first/base 3,527 domain; ModelManager critical DAG and 11.23 s pre-consume source window observed |
| Immutable primitive concept? | #222 raw output comparison supports a narrow pre-sprite representation |
| Nonduplicating, stock-observable late consumption? | **Fails** current 3.0.11 external consumer seam, #253 |
| New candidate runtime code / property? | **None**; no dormant feature flag |
| New same-origin 2 controls + 2 candidates? | **0 C / 0 B; not performed and not claimed valid** because no semantically admissible candidate exists |
| Actual changed-segment CPU with overhead? | **Not measured**; no changed segment was created |
| New allocation / GC / I/O / menu effect? | **Not measured**; no performance or resource claims |
| Physical machine equivalence? | **Pending/unavailable**; no laptop access and no new runtime candidate |
| New Actions exact-pack/startup run? | **Not requested** for documentation-only rejection; historic linked runs above are not fresh validations |

Never compare absolute menu timings across #217/#222/#250/#265 as paired A/B. #217's frame-elapsed exclusive attribution, #250's DAG wall and #265's exclusive CPU classifications have different observation definitions and executions. Do not sum inclusive listeners/futures, infer CPU seconds/heap from counts, or claim menu improvement from the available data. PR #336 per-call clocks are not used. No 2C/2B statistical acceptance claim is possible.

## Actual gate and follow-up if source contracts change

Effective workflows verified on current integration: `.github/workflows/build.yml` runs on PR/push; `startup-benchmark.yml` is path-filtered to runtime/build sources; `exact-pack-startup-benchmark.yml` PR plan requires `[exact-pack-ci]` and accepts **smoke/ab/paired** only. Its AB planner requires distinct candidate/control arguments and 1–5 repetitions. Do not invent an input, send a no-op property contrast, or accept green-without-activation.

**Minimum useful CI gate for this documentation-only PR:** ordinary Build success plus confirmation diff has only this research record and index, no runtime. No startup, exact-pack or real-hardware performance gate is meaningful until an executable semantically valid candidate exists; do not include `[exact-pack-ci]` in this PR body.

**Only reopening premise:** authoritative Decocraft source and permission/license to modify/distribute, plus a *Decocraft-owned* `BBModel → immutable PreparedBakeSequence → stock-owned buildQuads(sequence)` interface that removes the second ordered face traversal without suppressing material/sprite/`ModelState`, `BAKERY.bakeQuad`, `applyElementRotation`, error order or third-party injection surfaces. Exact version/binary/source pin, optional fail-open behavior, reload-generation ownership and atlas/GL worker exclusion are mandatory. Alternatively, a genuinely new audited **vanilla leaf** pure-stage/diagnostic replay contract may reopen a different family only after it resolves #265's zero-safe-closure blocker; it is **not** authorized by this document.

If a new pure API is established: source and callback/exception contract tests first; then proof the real prepare task begins/ends before stock first need, on an existing executor and without duplicate work; then full current-generation / complete-map NeoForge event equivalence and exact-pack smoke. **Before any performance acceptance**, require same-pack/JVM/origin/`main_menu` endpoint **2C+2B** with effective option activation and comparable telemetry, actual complete changed-path CPU including prepare/future/await/commit overhead, adjacent alloc/GC/I/O and ModelManager barrier/dependency union, and menu signal where claimed. For a code candidate AB PR body, use the actual recognized property in `exact-pack-mode: ab`, `exact-pack-repetitions: 2`, and real candidate/control JVM-arg lines; pin SHA and inspect every job/artifact. Physical visual/laptop evaluation remains with the principal after hosted gates. Do not move OpenGL to worker threads or defer NeoForge complete-map callbacks.

## Responsibility and final state

No code, tests, model caches, render work, optional mod source, PR #337 parent mechanism, or integration branch was modified. This research-only relay PR proposes a durable closure, not production optimization. The principal decides integration and physical work; there is **nothing to promote or leave default-off** here.

# Resource loading coverage audit — 2026-09-22

Status: **RESEARCH / measurement gaps identified; no runtime change**.

Authority refreshed with `git fetch origin`: integration
`b3f0c5f6462a359483883741ac16d2f154868900`. Independent worktree/branch:
`codex/resource-loading-audit-20260922`. The original checkout is dirty and
on an older promotion branch; its files are not the production authority.
The user's local architectural-program document was also read. This work
does not use relay coordination or agents and excludes launcher optimization.

## Finding

The resource reload is a strong focus, but it is not an unprofiled subsystem.
The main gap is **current, per-generation physical evidence**, especially
reload after resource-pack changes, rather than another first-start model
timer. Open diagnostic PRs contain substantial completed research absent
from integration's index. OPEN does not mean the investigation is still running,
nor does a successful diagnostic mean its code is integrated.

No new game launch, physical timing, visual validation or speedup is claimed
by this audit. Historical values below retain their original measurement
boundaries and cannot be combined across runs into a savings estimate.

Tooling verification on this PC: all 25 existing `tools/laptop-bench` Python
tests pass, all three integrated PowerShell scripts parse successfully, and
`git diff --check` passes. These checks do not validate a physical launch.

## Coverage map

| Zone | Existing evidence | Remaining measurement question / priority |
| --- | --- | --- |
| Request reload → pack discovery/selection/open/metadata → SimpleReloadInstance | #264 identifies the 128 PathPack empty-prefix errors as CITResewn's metadata probe; #103 validates selected packs | **High:** duration and ownership of the whole pre-listener interval, including old-manager close and new-manager construction. Error attribution does not time that interval. |
| Global preparation barrier and listener ordering | #47 full listener milestones; #148 physical rows; #214 structured model DAG; #216 later-listener turns | **High:** repeat on current PC workload and each reload generation. Reuse the milestone semantics rather than build another inclusive listener profiler. |
| Listeners before ModelManager | Earlier full census exists; #216 deliberately starts after ModelManager (historical index 12) | **Medium:** current identities, readiness and ordered slots for texture/language/font/sound and other early listeners; deepen only the actual gate or a material serial slot. They are not proven absent or wholly unmeasured. |
| Block-model/blockstate input preparation | #13/#47/#69/#138 plus #214 separate futures | **High if still gating:** bounded separation of resource discovery, winning-resource open/materialization, JSON and scheduling/CPU pressure on current physical hardware. |
| Mod-JAR resource open versus external ZIP enumeration | #71/#72/#136/#139 and #140/#141 cover different paths | **High physical gap:** distinguish UnionFS resolution, ZipFS eager materialization/inflate, storage wait and allocation. `open` wall is not automatically disk time. |
| External ZIP query/read reuse | #142 snapshot candidate, #182 exact-query audit, #187 replay | **Hold:** actual runtime repeated winning-entry/query distribution and barrier-relative cost remain missing; synthetic repeat-all replay is not runtime reuse. #142 has no convincing hosted reload delta and lacks physical candidate evidence. |
| Atlas source collection, sprite metadata/open/decode, stitch/mips/readiness | #72 sprite decomposition; #138/#214 aggregate atlas future | **Medium:** per-atlas last-finisher, readyForUpload/mipmap versus preparation, and contention on the model gate in later generations. Decode is already measured, not a blank front. |
| ModelBakery constructor | #217 disjoint scopes; #221/#257 classify residual as CIT lifecycle | **High for current attribution:** revalidate proportions after integrated #259 warning suppression and current pack. Fine-grained exclusive CIT parse/resolve/handler costs are not supplied by a log span. |
| Blockstate registration after indexed matching | #55 production matcher; #217 still finds material registration wall | **Medium:** residual grouping/multipart/predicate/dependency work if it remains large; do not repeat variant-scan indexing. |
| Actual uncached bake work | #217 family/owner and first-versus-repeat attribution | **Measured historically:** Decocraft first-base dominates one family; ordinary elements are also material. Need current physical proportions, not another identity census. |
| Safe generic model concurrency | #14/#36 rejected shallow routes; #248/#261 architectural audits; #265 census | **Closed under tested premise:** #265 proves zero *proven-safe* roots under its effect policy, not that every model is inherently impossible to parallelize. A new effect/equivalence proof is required. |
| Decocraft detached prepare | #222 regression, #250 availability window, #253 failed consumer seam | **Closed for that external seam:** early availability alone does not remove duplicate late traversal or preserve callbacks. Do not rebuild the same IR experiment. |
| ModelManager apply/upload/publication | #214 owner-thread commit and ordered wait | **Medium physical gap:** split CPU submission/callback/publication from native GPU waits only if current commit is material. No GL moves; hosted llvmpipe is not a PC GPU measurement. |
| Post-ModelManager ordered tail | #184/#189/#193/#216 identify owners | **Measured historically:** FancyMenu, MoreCulling, entity renderers dominate; current hardware/generation redistribution is missing. Not an ownerless ten-second residual. |
| Shader/renderer subpaths | #85 LevelRenderer, #118 shader fallback, #190 renderer providers, #197 Flywheel | **Conditional:** preserve old evidence; only deepen a material current slot. Historical low hosted wall does not settle Windows/GPU-sensitive cost. |
| allDone → overlay removal → actual presented usable screen | #219 measures real screen and finds AnalogAudio welcome modal | **High correctness gap:** distinguish TitleScreen opening, actual presented screen, overlay disappearance and usable input. Menu-opening event is not proof of usable menu. |
| Subsequent same-JVM reload, changed packs, restored packs | Production has invalidation hooks, but #214/#216 deliberately observe first generation only | **Highest new coverage gap:** per-generation workload, cache reset, old resource close, new publication, presentation, memory retention and errors. |
| World-loaded resource reload and first use | Program requires representative gameplay; native-crash investigations exist | **High semantic gap:** current models/textures/CIT/entities after reload and first-world usability. A menu-only smoke cannot certify these. |

Outside this chosen lane, FML/discovery/transform/construction have numerous
diagnostics (#202–#211, #231–#247, #270–#279); they are not assumed unmeasured.
Their current physical attribution and complete interaction/scaling matrix
are still separate unfinished program work. Launcher/AppCDS/pre-Java work
belongs to the other task and is not included here.

## Strong evidence and corrections to preserve

- Physical corrected P0.2: initial reload **186.710 s**, ModelManager final
  future **150.078 s**, block models **24.960 s**, atlas **34.314 s**,
  bakery **65.460 s**, loadModels **46.874 s** containing bake **41.153 s**.
  These overlap/nest. The model chain, not atlas readiness, was the direct
  critical branch. The later diagnostic sample also preserves that ordering.
- #214 hosted run `34290311738`: reload **37.268 s**; global preparation
  **25.613 s**, ModelManager preparation **25.572 s**; stock apply **0.420 s**.
  ModelManager arrived about **16.56 ms** before global preparation opened.
  Its lexical dependency union **19.305 s** is not the full async reload path.
- #216 hosted run `34292688300`: post-ModelManager **10.870 s** is explained
  by ordered turn slots, with FancyMenu-correlated final listener **4.666 s**,
  MoreCulling slots **2.588 s** and **0.817 s**, entity renderers **1.762 s**.
  These are one run's slots, not reusable hardware constants.
- #217 reports **7.516 s exclusive uncached elapsed work**: Decocraft
  **3.778 s**, ordinary elements **1.762 s**, multipart **0.854 s**, generated
  items **0.616 s**. Decocraft first-base **3.227 s** versus repeats **0.550 s**.
  Do not silently rename elapsed instrumentation as measured CPU time.
- #221's supposed inactive CIT cache was corrected in its comments:
  **3960 requests / 3944 hits / 3944 open bypasses**. #257 and #267 confirm
  the deployed historical `schm.shsupercm` target; public newer-fork source
  is not the exact fixture binary. No retarget is justified.
- #72 laptop sprite task-sum: **76.126 s inside Resource.open**, only
  **0.116 s reads after open**, **3.113 s STB decode**. UnionFS → generic
  newInputStream → ZipFS newByteChannel can eagerly inflate/materialize bytes
  inside open. This is not 76 s critical wall or proof that STB is the target.
- #142 physical enumeration evidence **1.321–5.276 s inclusive** remains
  relevant despite the hosted reload median delta of only **−0.103 s**.
  Disposition: candidate lacks physical evidence; no promoted cache.
- #219's first presented UI is **LavaplayerWelcomeScreen**, not the requested
  TitleScreen. Its **2.840 s allDone→startup_ui_presented** cannot be renamed
  allDone→navigable menu. Record actual local modal state before measuring.

## Source audit: why later reloads require an extension

Integration's `boot_optim.mixins.json` contains production compatibility and
model optimizations, not #214's DAG producers. `ClientStartupHooks` observes
TitleScreen opening, with an optional immediate stop; it has no per-reload
presentation endpoint.

At #214 head `0448e62bbf08c048249376fcb0d8718ef736501b`,
`ResourceReloadDagTrace.isStartupGeneration` explicitly requires generation
1. `ACTIVE_MODEL_GENERATION` and `LAST_LOAD_MODELS_TASK` are global scalars.
Merely deleting the generation-1 check would not constitute a safe multi-reload
implementation: task dependencies, concurrent/failed reloads, record lifetime,
startup endpoint and summary/flush lifetime all need review.

Production CIT base-model caching clears its map at ModelBakery constructor
HEAD; indexed matching creates/removes a per-load ThreadLocal context. Those
are source-level lifetime mechanisms, not experimental proof of changed-pack
equivalence or absence of retained data after a failed reload. Test those
properties rather than assuming a cache bug exists.

## Recovered physical tooling and boundaries

Reusable integration files under `tools/laptop-bench/`:

- `remote_laptop_transaction.ps1`: Preflight, Stage, Run, Postflight, Recover;
- `remote_laptop_interactive_run.ps1`: active interactive session, effective
  JVM-token validation, process ownership and blocking wait after acceptance;
- `check_resource_selection.py`: ordered resource-list validation;
- `check_measurement_result.py`: startup provenance/endpoints;
- `phase_variance.py` and optional `remote_laptop_host_trace.ps1`.

Preserve exactly-one packaged bootstrap, no stale target Java, Prism stopped
before editing instance.cfg, effective Java executable/arguments and hashes,
post-exit offline analysis, exact restoration. Never persist an account-bearing
raw command line. Do not poll logs/hash the pack during timing.

Read-only local discovery found no Prism at the checked conventional paths
and did find Pandora's local application directory. This is **not** proof
that no portable Prism exists. No instance has been selected, modified or
launched, and the laptop has not been contacted in this audit.

The startup validator expects one initial-resource marker and a menu endpoint.
The resource checker evaluates selected lists but does not express an A→B→A
per-generation expected manifest. Extend with a separate reload-result
contract; do not weaken startup validity to accept ambiguous multi-reload logs.
Use the newer #268 Prism argument hardening when preparing a physical run,
after reviewing/reconciling its changes with the integrated transaction.

## Bounded next measurement campaign

1. **Freeze PC workload first.** Locate the intended portable Prism/instance or
   create an isolated exact-pack instance. Pin launcher binary/version, Java,
   packaged BootOptim hash, mod/fork versions, ordered resource selection,
   options, AnalogAudio modal state and FancyMenu selection policy. A current
   pack and the historical September-2 fixture are different populations.
2. **Adapt existing coarse DAG diagnostics on a diagnostic branch based on
   current integration.** Port only needed #214/#216 observer semantics and
   trace prerequisites, not entire old experimental branches. Add reload
   request/manager-replacement boundary, all-listener readiness/turns and
   generation-specific final presentation. Default off; bounded rows per
   listener/generation; unchanged original futures/executors/ordering.
3. **One diagnostic PC session:** initial generation G1, unchanged-pack G2,
   deliberate reversible pack change G3, restoration G4. Trigger through the
   stock reload path; record exact ordered selection for each. Same-JVM warm
   reload is a separate metric, never a substitute for startup TTMM.
4. **Validate per generation:** same process origin, unique request/start/end,
   non-overlapping ownership or explicit concurrent-reload rejection, completed
   barriers, no dropped events, no new errors, correct atlas/resource identities,
   actual screen/overlay presentation. Failure/recovery must remain visible.
   Record CPU/GC snapshots at coarse boundaries; wall minus CPU is not I/O.
5. **Choose one exclusive cost from that session.** If input/model branch
   gates, deepen only it; if another listener or pre-listener work gates, follow
   that owner. Do not add per-read/per-face clocks across the whole pack.
6. **Separate timed validation and semantics.** Detailed trace disabled during
   later A/B. Validate representative models/CIT/entities and world reload in
   an isolated test world. Only use the laptop for a bounded follow-up whose
   physical question is explicit; no reboot/cache purge by default.

Priority decision: **measure generations and request-to-listener coverage
first; keep model preparation as the leading optimization suspect.** The
next deliverable is a diagnostic capability and valid PC evidence, not a
generic cache, executor cap, or claim that all old NO-GOs are permanent.

## Evidence links

Read PR bodies and result/correction comments, not titles alone:

- DAG/tail: [#47](https://github.com/wachipayox/BootOptim/pull/47), [#214](https://github.com/wachipayox/BootOptim/pull/214), [#216](https://github.com/wachipayox/BootOptim/pull/216), [#219](https://github.com/wachipayox/BootOptim/pull/219).
- Models/CIT: [#217](https://github.com/wachipayox/BootOptim/pull/217), [#221](https://github.com/wachipayox/BootOptim/pull/221), [#248](https://github.com/wachipayox/BootOptim/pull/248), [#250](https://github.com/wachipayox/BootOptim/pull/250), [#253](https://github.com/wachipayox/BootOptim/pull/253), [#257](https://github.com/wachipayox/BootOptim/pull/257), [#261](https://github.com/wachipayox/BootOptim/pull/261), [#265](https://github.com/wachipayox/BootOptim/pull/265).
- Resources: [#72](https://github.com/wachipayox/BootOptim/pull/72), [#136](https://github.com/wachipayox/BootOptim/pull/136), [#139](https://github.com/wachipayox/BootOptim/pull/139), [#142](https://github.com/wachipayox/BootOptim/pull/142), [#182](https://github.com/wachipayox/BootOptim/pull/182), [#187](https://github.com/wachipayox/BootOptim/pull/187), [#264](https://github.com/wachipayox/BootOptim/pull/264), [#267](https://github.com/wachipayox/BootOptim/pull/267).
- Physical: [variance](modelmanager-physical-variance-2026-09-06.md), [executor experiment](resource-reload-pool-laptop-2026-09-07.md), [transaction contract](remote-laptop-operation-audit-2026-09-07.md).

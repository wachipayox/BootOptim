# BootOptim research ledger

- [ModelBakery construction allocation attribution — 2026-10-14](modelbakery-allocation-attribution-2026-10-14.md): JFR by stack; constructor is 13.7 % of startup allocation, top site is the already-retired multipart union; GC ceiling ~0.6 s hosted. NO-GO. Exact-pack CI now uploads `*.jfr`.

- [Agent 216 point-resource resolution plan NO-GO — 2026-10-11](resource-resolution-plan-no-go-2026-10-11.md): rejects repeated-lookup caches and speculative provider plans before runtime; source/callback gates unmet, no invented 2C/2B or menu improvement.

- [External report audit and clean JSON promotion — 2026-10-02](web-report-disposition-2026-10-02.md): distinguish new premises from #142/#182/#248 history; no generic cache without a current budget.

Final Ferrite late-presize decision: [retired after physical gate — 2026-10-01](ferrite-presize-final-decision-2026-10-01.md). This supersedes the earlier hosted segment-retain/pending-promotion status; no active Ferrite candidate remains.

Current binary dispositions: [owner candidate final decisions — 2026-10-01](owner-candidate-final-decisions-2026-10-01.md). This supersedes older default-off/pending-laptop registers for Decocraft V2, layer arithmetic, multipart union and persistent Ferrite empty storage.

This directory is the durable memory for startup-performance research. Its purpose is to prevent the project from repeatedly rediscovering the same bottleneck, optimization idea, or negative result after profiling branches are closed or left unmerged.

Before starting a new optimization experiment, check this ledger and the linked PRs. A previously rejected direction may be reopened only when the new hypothesis changes a material premise (different critical path, different implementation, different version, or evidence that the old measurement no longer applies). "The phase is still slow" by itself is not enough to repeat an experiment that already showed poor wall-clock leverage.

Repository agents must also read the root [`AGENTS.md`](../../AGENTS.md) and inspect both open and closed PR history for the subsystem before changing code. A successful experiment can remain unmerged; the integration tree is the authority for what is actually present.

Every result in this ledger must identify its measurement origin and endpoint.
Do not compare hosted, physical-laptop and fast-PC timings as if they were the
same population, and do not mix `main_menu` with `main_menu_presented`.
Launcher/fixture warm-up, process entrypoint, resource-reload critical-path
markers, post-menu presentation and inclusive listener/task-sum timings remain
separate buckets; overlapping values are never summed. On the Prism laptop,
stop Prism before editing `instance.cfg` and confirm the effective JVM command
line afterwards, because Prism may rewrite stale in-memory arguments on exit.

- [Temporary material-resolution chain list, relay 214 final rejection — 2026-10-11](material-chain-list-lazy-2026-10-11.md): valid 2C/2B on the actual patched method shows mixed-sign net whole-bake CPU despite lower allocation; retired with no production change.

Branch-only evidence from unmerged experiment/diagnostic branches (229 files, harvested 2026-10-14 before branch/PR cleanup) is preserved verbatim in [`docs/archive/`](../archive/README.md). It is historical and not mandatory reading; this ledger stays authoritative.

## Pre-entrypoint phase (JVM → FML → ModLauncher → mod entrypoint): archived summary

Context: this phase is ~31 s on hosted exact-pack but ~124–180 s on the physical laptop. It was mined heavily in September 2026; every document below lives in [`docs/archive/research/`](../archive/research/) and **none left a runtime candidate**. Read a file only if you reopen its exact premise.

- **Discovery / JarJar / scan:** generic dependency/JarJar result cache rejected (#134: locators depend on runtime state); copy+SHA JiJ sidecar rejected for FML 4.0.43; background scan is already a prepare/barrier design; root-wrapper discovery replay NO-GO; ModFile metadata re-read proven duplicate but hosted ceiling too small (reopen only with physical HDD evidence). Files: `discovery-artifact-critical-path`, `fml-dependency-discovery-cache-boundary`, `fml-4.0.43-jij-*`, `fml-background-scan-barrier`, `root-wrapper-discovery-replay`, `fml-modfile-metadata-reread`.
- **Construction / registration:** construction scheduler rejected; mod-container registry attribution closed; AT collection loop tiny — the only credible route is an upstream AccessTransformers-engine change (test contract exists, no BootOptim cache). Files: `fml-construction-*`, `fml-registry-attribution-pr99-final`, `fml-registration-publication`, `access-transformer*`, `create-registrate-callback-frontier`, `registry-block-cpu-attribution`.
- **Mixin / ModLauncher:** per-config prepare NO-GO; opcode-name lookup micro-candidate NO-GO; Bootstrap "double transform" explained (not two definitions); pre-bootstrap redundancy NO-GO without an upstream contract; post-accept ModLauncher fork NO-GO as optimization. Files: `mixin-*`, `modlauncher-*`, `post-discovery-pre-transformers-residual`, `pre-minecraft-bootstrap-transition`, `minecraft-bootstrap-causal-trace`.
- **Connector:** warm transform cache already hits 3/3; split-package residual NO-GO. Files: `connector-*`.
- **Class-data sharing:** AppCDS retired (+3.8 s physical median, #290); pre-generated distributable archive NO-GO; JDK 25 AOT cache NO-GO (#293). Files: `appcds-*`, `pandora-appcds-net-benefit`, `jdk25-aot-cache-laptop`.
- **Physical scaling / I/O:** `early-fml-physical-variance-boundary` leaves one unmeasured BootOptim-owned frontier (wrapper lookup fallback in `DiscoveryStartLocator`); `decocraft-jar-readahead-ceiling` was left as ACTIVE DIAGNOSTIC without a decision (**pending closure**, tracked in wbombo). Other scaling/trace files (`early-modloading-scaling`, `laptop-startup-scaling`, `bootstrap-variance-boundary`, `fml-*-causal-trace`, `fml-lifecycle-*`) are observability only.

Reopen rule: a new premise must name which bucket above it differs from and why the old evidence does not apply.

## PRs closed in the 2026-10-14 cleanup

These PRs were open with decisions already reached or superseded. Their documents are preserved in [`docs/archive/`](../archive/README.md); the PR stays the place for code and discussion.

- [#336](https://github.com/wachipayox/BootOptim/pull/336) — [model-ancestry-owner-2026-10-03](../archive/research/model-ancestry-owner-2026-10-03.md): Model ancestry/texture-resolution owner diagnostic. Counts usable, exclusive CPU tiny; not a budget. Follow-ups (relays 214–218, #337) closed without a runtime candidate.
- [#324](https://github.com/wachipayox/BootOptim/pull/324) — [palladium-constructor-shortcuts-2026-10-02](../archive/research/palladium-constructor-shortcuts-2026-10-02.md): **NO-GO** for a BootOptim runtime patch: Palladium constructor shortcuts rejected by binary semantic audit.
- [#323](https://github.com/wachipayox/BootOptim/pull/323) — [shader-input-reuse-audit-2026-10-02](../archive/research/shader-input-reuse-audit-2026-10-02.md): **REJECTED**: generic cross-program shader include-text caching (provider semantics).
- [#322](https://github.com/wachipayox/BootOptim/pull/322) — [ferrite-quad-hash-arithmetic-2026-10-02](../archive/research/ferrite-quad-hash-arithmetic-2026-10-02.md): **RETIRED**: Ferrite hash arithmetic, no demonstrated game saving after valid actual-method replay.
- [#321](https://github.com/wachipayox/BootOptim/pull/321) — [jei-index-edge-promotion-audit-2026-10-02](../archive/research/jei-index-edge-promotion-audit-2026-10-02.md): **REJECTED**: JEI edge clone skipping (exact-binary callback-order counterexample).
- [#320](https://github.com/wachipayox/BootOptim/pull/320) — [decocraft-json-owner-decision-2026-10-01](../archive/research/decocraft-json-owner-decision-2026-10-01.md): Decocraft JSON owner diagnostic; superseded by the clean production promotion #325.
- [#306](https://github.com/wachipayox/BootOptim/pull/306) — [runner-variance-calibrated-trials-2026-10-01](../archive/research/runner-variance-calibrated-trials-2026-10-01.md): Tooling proposal for a calibrated within-JVM evidence gate (A/A noise). Not adopted as a required gate.
- [#304](https://github.com/wachipayox/BootOptim/pull/304) — [inprocess-candidate-decision-2026-09-30](../archive/research/inprocess-candidate-decision-2026-09-30.md): Counterbalanced within-JVM decision diagnostic for the 2026-09-30 express candidates; decisions superseded by owner-candidate-final-decisions-2026-10-01.
- [#303](https://github.com/wachipayox/BootOptim/pull/303) — [model-grouping-attribution-2026-09-30](../archive/research/model-grouping-attribution-2026-09-30.md): Multipart/blockstate attribution diagnostic; dependency owner already profiled, loops/unions retired (#295/#305/#313).
- [#302](https://github.com/wachipayox/BootOptim/pull/302) — [laptop-express-sweep-2026-09-30](../archive/research/laptop-express-sweep-2026-09-30.md): Unattended laptop express sweep; combined ABBA order confound recorded; superseded by owner-candidate final decisions.
- [#297](https://github.com/wachipayox/BootOptim/pull/297) — [hosted-repeat-reload-2026-09-30](../archive/research/hosted-repeat-reload-2026-09-30.md): Same-JVM hosted repeat-reload harness smoke; diagnostic only.
- [#293](https://github.com/wachipayox/BootOptim/pull/293) — [jdk25-aot-cache-laptop-2026-09-25](../archive/research/jdk25-aot-cache-laptop-2026-09-25.md): **NO-GO**: JDK 25 AOT cache for the current private launcher profile.
- [#291](https://github.com/wachipayox/BootOptim/pull/291) — [pandora-prelaunch-attribution-followup-2026-09-25](../archive/research/pandora-prelaunch-attribution-followup-2026-09-25.md): Pandora prelaunch attribution; superseded by Pandora PR #66 (persistent per-instance `.minecraft`, no per-launch mods rebuild).
- [#290](https://github.com/wachipayox/BootOptim/pull/290) — [pandora-appcds-net-benefit-2026-09-25](../archive/research/pandora-appcds-net-benefit-2026-09-25.md): **RETIRED**: full-archive AppCDS (four physical pairs, +3.8 s median regression).
- [#289](https://github.com/wachipayox/BootOptim/pull/289) — [rrls-fancymenu-early-menu-2026-09-24](../archive/research/rrls-fancymenu-early-menu-2026-09-24.md): Do not ship upstream RRLS early-menu with this pack; work continues in the private RRLS/FancyMenu forks.
- [#288](https://github.com/wachipayox/BootOptim/pull/288) — [decocraft-sprite-archive-batch-2026-09-23](../archive/research/decocraft-sprite-archive-batch-2026-09-23.md): **NO-GO** for promotion: Decocraft encoded sprite PNG batch.
- [#287](https://github.com/wachipayox/BootOptim/pull/287) — [decocraft-sprite-archive-batch-2026-09-23](../archive/research/decocraft-sprite-archive-batch-2026-09-23.md): Default-off sprite batch experiment; closed by #288.
- [#286](https://github.com/wachipayox/BootOptim/pull/286) — [decocraft-model-archive-batch-2026-09-23](../archive/research/decocraft-model-archive-batch-2026-09-23.md): Physical reload harness for the Decocraft model JSON batch; superseded by production #325.
- [#285](https://github.com/wachipayox/BootOptim/pull/285) — [decocraft-model-archive-batch-2026-09-23](../archive/research/decocraft-model-archive-batch-2026-09-23.md): Default-off Decocraft model JSON batch experiment; superseded by production #325.
- [#284](https://github.com/wachipayox/BootOptim/pull/284) — [model-input-io-lane-2026-09-23](../archive/research/model-input-io-lane-2026-09-23.md): Resource-reload I/O/GC physical evidence; two-permit model input lane **REJECTED** before A/B; input-cache design remains a hypothesis.
- [#282](https://github.com/wachipayox/BootOptim/pull/282) — [resource-loading-coverage-audit-2026-09-22](../archive/research/resource-loading-coverage-audit-2026-09-22.md): Manual resource-pack reload I/O/GC attribution diagnostic; no runtime change.

[#292](https://github.com/wachipayox/BootOptim/pull/292) Xaero World Map 1.41.0 startup version-check skip: **promoted to production** on 2026-10-14 by user decision; see [`optimizations/compatibility/xaero-startup-update-check.md`](../optimizations/compatibility/xaero-startup-update-check.md).

## Status vocabulary

- **PROFILED** — diagnostic evidence only; no optimization conclusion yet.
- **ACTIVE** — currently being investigated.
- **VALIDATED** — mechanism and real-pack benefit were validated strongly enough to remain a production candidate.
- **LIMITED** — optimization works locally/in a subphase, but wall-clock leverage is small or highly overlapped.
- **REJECTED** — do not repeat the same mechanism without a new premise.
- **SUPERSEDED** — a later experiment or profiler replaced the old interpretation.

## What every research entry must record

1. Target phase and why it was believed to be on the startup critical path.
2. Exact hypothesis and mechanism, not just the class or method name.
3. Measurement environment and the important before/after numbers.
4. Whether the measurement is CPU time, inclusive wall time, or actual critical-path wall time.
5. Compatibility / semantic risks introduced by the experiment.
6. Result and reason for promotion, limitation, or rejection.
7. Reopening criteria: what new evidence would make the same direction worth revisiting.
8. Links to the relevant profiling/experiment PRs.

## Research index

- [Game-side lexical prefix IO audit — 2026-10-01](path-prefix-io-audit-2026-10-01.md) — actual directory/ZIP/filter/priority/dynamic-file/closed-pack contracts pass offline; no runtime or performance claim.

- [Generated-item pixel-query reuse rejection — 2026-09-30, closed 2026-10-01](generated-item-pixel-query-followup-2026-09-30.md) — retired: identical topology is insufficient when alpha callbacks change from 12 to 4 and change order; no runtime candidate retained. Reopen only with a changed, proven purity premise.

- [Segment-first binary decision policy — 2026-10-01](segment-first-decision-policy-2026-10-01.md) — current user override: two controls/two candidates, actual optimized-segment evidence first, attributable indirect costs next, then integration or documented removal; no indefinite experimental retention.

- [MCEF screen-transition sleep during first-consumer deferral — 2026-09-28](mcef-screen-transition-sleep-2026-09-28.md) — exact-pack fast-PC watchdog attributes repeated ~1-second menu navigation gaps to MCEF's delayed automatic initializer while BootOptim keeps CEF deferred; scoped skip passed hosted startup and physical menu/world validation.

- [Laptop shader fallback and Voxy-save variance — 2026-09-05](laptop-shader-voxy-variance-2026-09-05.md) — **REJECTED** physical diagnostic: five deliberate shader-capability failures cost 155 ms wall/62.5 ms CPU, while Voxy saved once in 5.5 ms with no concurrency; neither is an actionable startup target.

- [Post-FancyMenu preload critical-tail audit — 2026-09-05](post-fancymenu-critical-tail-audit-2026-09-05.md) — **LIMITED / NO-GO**: current integration has coarse preload/reload/title endpoints but lacks the #47-style scheduler barrier and first-present boundary needed to attribute the variable physical tail; no safe runtime optimization is identified.
- [Fixed laptop FancyMenu wait-CPU evidence — 2026-09-05](laptop-fancymenu-wait-cpu-variance-2026-09-05.md) — full-pack physical diagnostic proving material current-thread CPU in stock FancyMenu waits on the 2C4T software-renderer laptop; candidate remains gated by semantic and hosted A/B tests.
- [Full-resource laptop variance](laptop-fullpack-variance-2026-09-05.md) — validated workload, control/candidate/control timing partitions, and the fixed-configuration causal diagnostic gate; no physical MCEF win established.
- [CITResewn base item-model parse cache — 2026-09-06](citresewn-base-item-model-cache-2026-09-06.md) — active optional experiment targeting repeated `models/item/*.json` parses inside the item-CIT reload path; hosted and physical validation pending.

- [FancyMenu usable-menu / resource-preload boundary — 2026-09-05](fancymenu-usable-menu-resource-boundary-2026-09-05.md) — exact 3.9.0 bytecode proves panorama/slideshow/native-video consumers and title audio can tolerate not-ready resources, but `ResourcePreLoader.preLoadAll` exposes no safe continuation that preserves ordered start/wait/timeout/error semantics; BootOptim runtime defer rejected.

- [Laptop resource-selection audit](laptop-resource-selection-audit-2026-09-05.md) — retained laptop runs omitted external ZIPs; correct the workload contract before exact-pack performance claims.
- [VoxelShaper limited safe-domain closure — 2026-09-05](voxelshaper-safe-domain-2026-09-05.md) — **REJECTED #110**: `<=2` boxes is strict-safe but owns only 0.097% of measured stock fold CPU; the broader epsilon-stable domain covered about one quarter of stock fold CPU but failed strict equivalence with two natural and one exact-dyadic adversarial counterexample. Coverage is not TTMM savings; no A/B or laptop run was justified.
- [MoreCulling reload-local translucency reuse — 2026-09-05](moreculling-translucency-reuse-2026-09-05.md) — **LIMITED / REJECTED #119**: exact reload-local identity+bounds semantics passed smoke, but two hosted 3×3 campaigns contradicted each other (+15.005 s and −2.774 s TTMM); no stable end-to-end benefit and no laptop gate.

- [Hosted exact-pack startup CI](exact-pack-ci.md) — pinned exact software-pack fixture, Linux/Xvfb/llvmpipe surrogate boundaries, deterministic MCEF setup, PR-body A/B protocol, and real-hardware gate rules.
- [FancyMenu final fork validation on the physical laptop — 2026-09-06](fancymenu-final-fork-validation-2026-09-06.md) — corrected option-enabled pair confirms the retained active-layout filter; the earlier all-source pair was invalid for that claim.
- [FancyMenu global panorama prelaunch — 2026-09-06](fancymenu-global-panorama-prelaunch-2026-09-06.md) — generic full-list overlap is validated but adds no gain to the current final pack, which already selects one source and overlaps its six faces.
- [P0.2 physical-laptop variance harness — 2026-09-06](p0.2-variance-harness-2026-09-06.md) — corrected harness baseline attributes the critical path to the CPU/GC-heavy resource/model pipeline, especially ModelManager; one run is not a variance distribution.
- [P2 WebDisplays/MCEF physical run — native OpenAL crash — 2026-09-06](p2-webdisplay-openal-native-crash-2026-09-06.md) — repeated world-entry closure is a native OpenAL diagnostic, not attributed to BootOptim.
- [Laptop resource-reload executor experiment — 2026-09-07](resource-reload-pool-laptop-2026-09-07.md) — a two-worker HDD policy reached the intended executor but lost the clean physical A/B and is rejected as production.
- [GitHub fresh-VM variance and same-VM paired diagnostic — 2026-09-07](github-fresh-vm-variance-2026-09-07.md) — the ElementsModel campaign's mixed-sign fresh-VM result, the paired diagnostic mode, alternating order, and the evidence gate before another laptop run.
- [Production optimization retention audit — 2026-09-03](production-retention-audit-2026-09-03.md) — verifies the six pre-MCEF production mechanisms are still physically present and unchanged, records exact mixin/helper blobs and latest runtime markers, fixes two catalog omissions, and records the two independent current-head MCEF 3×3 confirmations.
- [ModernFix reload parallelism lease audit — 2026-09-06](modernfix-reload-lease-audit-2026-09-06.md) — **REJECTED AS PRODUCTION**: #126's -242 ms reload median is not stable per sample, the reversed laptop pair did not reproduce it, TTMM is dominated by unrelated drift, no CPU metric proves contention relief, and no safe adaptive controller is justified; retain only as inconclusive hardware-sensitive evidence.
- [Client model / ModelManager pipeline](model-pipeline.md) — historical #13/#14/#35/#36/#37/#47 evidence, the validated #55 blockstate matcher, and rejected shallow approaches.
- [ModelManager physical variance / critical reload audit — 2026-09-06](modelmanager-physical-variance-2026-09-06.md) — corrected laptop evidence places the current critical branch in ModelBakery/load/bake, rejects another generic executor/cache patch, and defines the minimum three-run attribution gate.
- [Deep ModelManager follow-up — 2026-08-31](model-pipeline-deep-2026-08-31.md) — 97.60% recursive bake-cache hit rate, exclusive bake-cost attribution, 10.86M blockstate variant tests, and the indexed-matching architectural hypothesis.
- [Post-promotion ModelManager residuals](modelmanager-post56-residuals.md) — PR #57 exact-pack gate/cost distribution after production promotions, rejected generated-item span-topology cache, and the short-scope material-resolution hypothesis.
- [Decocraft 3D item / item-sprite elision — 2026-09-02](decocraft-3d-item-sprite-elision-2026-09-02.md) — rejected #79 experiment: 3,192 verified model/sprite removals, laptop end-to-end tie, hosted 3×3 regression, atlas-environment boundary, and reopening criteria.
- [FancyMenu rolling panorama window — 2026-09-02](fancymenu-panorama-window2-2026-09-02.md) — rejected #83 follow-up: window=2 successfully launched 18 next panoramas but moved the hosted panorama median only ~127 ms and left reload→FancyMenu effectively unchanged; production six-face overlap remains retained.
- [FancyMenu initial-layout resource preload — 2026-09-05](fancymenu-initial-layout-preload-2026-09-05.md) — retained in the user-owned `3.9.0-wedit` fork after the physical control-002/candidate-006 pair; it stages the title-screen random selection and skips 21 of 22 unselected panorama/slideshow sources during the first reload.
- [MCEF initial resource-reload overlap — 2026-09-02](mcef-initial-reload-overlap-2026-09-02.md) — rejected #78 experiment: native CEF/resource-preparation overlap made CEF ~2.9x slower and regressed laptop post-entrypoint TTMM by ~10.2 s; first-consumer/lazy initialization remains a materially different premise.
- [MCEF gameplay-boundary audit — 2026-09-05](mcef-gameplay-boundary-2026-09-05.md) — source-validates serial pre-gameplay preparation points before local `MinecraftServer.spin` and remote connector startup, identifies PR #90 callback-reentry/30 s wait risks, and defines hosted + Windows gates before claiming first-use WebDisplays is hitch-free.
- [MCEF first-consumer defer — 2026-09-03](mcef-first-consumer-defer-2026-09-03.md) — validated #88/#89 premise and production #90 confirmation: keep the real MCEF lifecycle but defer CEF until an actual consumer; the original hosted 3×3 improved ~1.994 s and two independent 3×3 campaigns on production head `d45df21b` independently improved ~1.051 s and ~2.202 s.
- [LevelRenderer resource-reload split — 2026-09-03](levelrenderer-reload-split-2026-09-03.md) — #85 attribution: current slow-laptop listener wall is ~2.061 s, dominated by ~1.458 s `entity_outline` PostChain load; the old ~6.507 s trace is not a valid current savings ceiling, so defer work is secondary to MCEF.
- [Indexed blockstate variant matching](blockstate-indexed-matching.md) — PR #55 exact-pack validation of the indexed replacement for the 1.21.1 O(variants × possible states) candidate scan, including 110,053 stock-equivalent variants with zero mismatches.
- [Production optimizations](production-optimizations.md) — startup optimizations that crossed the evidence bar and are intended to live in integration.
- [Mixin / ModLauncher transformation pipeline](mixin-pipeline.md) — #41/#42/#43/#46/#48 evidence, including the rejected generic side-load cache, confirmed-but-irrelevant ClassInfo negative-cache bug, and rejected external ASM writer-tail target.

## Project rule

A large count reduction is not sufficient evidence of startup improvement. BootOptim optimizes time-to-main-menu, so experiments must ultimately be judged by their contribution to the real critical path. CPU work that is cheap per call or hidden under another concurrent gate can be worth documenting without being worth shipping.

- [Exact ZIP query owner budget](resource-query-budget-2026-10-02.md): diagnostic closes missing exact-repetition and callback-excluded CPU evidence from #182; no resource cache.

- [ZIP prefix final decision and clean promotion](zip-prefix-final-decision-2026-10-03.md): physical and hosted owner CPU evidence, mixed global/GC effects, strict lifetime guards.

- [Lexical PathPackResources prefix final decision](path-prefix-reuse-candidate-2026-10-03.md): retired after hosted and physical gates; small replay ceiling does not measure candidate net CPU; no forgotten runtime flag.

- [Laptop atomic state sharing recovery](laptop-atomic-state-sharing-2026-10-03.md): bounded retry of transient Windows read-handle contention; physical four-run harness passed.

- [Strict path-segment scan and physical decision](strict-path-segment-scan-2026-10-03.md): retained guarded pure-operation CPU removal, clean promotion pending gates.
- [Point-resource resolution and mutable CIT owner](resource-resolution-owner-2026-10-03.md): completed #332, no result/model cache promoted.

- [Lazy parent-cycle local: retired](parent-cycle-lazy-2026-10-11.md): actual 2C/2B traversal allocation -93.09%, CPU showed no net win; no runtime code retained.

- [CITResewn fresh-model syntax reuse — rejected](citresewn-fresh-model-syntax-reuse-2026-10-11.md): preimplementation callback/budget rejection; no new performance candidate or runtime switch.

- [Decocraft pure preparation and ordered commit — rejected](modelmanager-pure-prepare-ordered-commit-2026-10-11.md): current consumer seam cannot remove duplicate work while preserving stock behavior; no new runtime candidate.

- [Non-ZIP PathPackResources catalogue — rejected](non-zip-pathpack-enumeration-2026-10-11.md): new diagnostic census; class/root identity cannot prove immutable contents or filters, no runtime catalogue retained.

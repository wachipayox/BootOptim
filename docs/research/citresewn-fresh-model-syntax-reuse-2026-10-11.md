# CITResewn repeated model syntax, fresh construction — relay 217 (2026-10-11)

**Disposition: REJECT / DO NOT IMPLEMENT on current evidence.** This is a bounded *source-and-budget feasibility rejection*, not a measured negative candidate A/B. No optimization, experimental property, Mixin, parser wrapper, model cache, profiling hook or CITResewn JAR is introduced. The already-retained read-only base model cache (#151) remains untouched. No exact-pack or physical measurement was run for this decision.

## Authority, distinct boundary, prior decisions

- Integration refreshed on 2026-10-11: `agent/integration-current` at `058ac544aef11c0c10dc32e0aeacba2e39176d40`. Compare to the integration tree, not an experimental PR head.
- Read `AGENTS.md`, root README, both documentation indexes, `exact-pack-ci.md`, segment-first policy, owner final decisions, and `web-report-disposition-2026-10-02.md`. That last document explicitly requires evidence of repeated *current* input/parser work before an additional parse cache.
- Reviewed open/closed subsystem PR bodies and discussions: retained #151; target/package-drift audit #223; lifecycle NO-GO #257; exact-CIT-binary provenance NO-GO #267; point-resolution and mutable-parse diagnostic #332; structural model plans #181/#196 and constructor/deserializer profiling #183/#184; live separate ancestry diagnostic #336 and lazy-parent experiment #337. Rejected #43 side-load memoization and #303/#305/#313 multipart dependency experiments do not supply transferable parse-cache evidence.
- #337 owns parent-cycle-set allocation. This report neither replicates its source rewrite nor instruments that stage. #336's costly per-call time sampling is specifically **not** reused as an alleged exclusive model-parse budget.

The distinct boundary is the *second* CITResewn model path: `TypeItem.getModelForFirstItemType` and `TypeItem.getModelFromOverrideModel` execute resource selection, `Resource.open`, UTF-8 `IOUtils.toString`, `BlockModel.fromString`, then **mutate each newly created model** (`name`, spawn-egg texture state, and synthetic generated IDs), with exception-to-null behavior. This is not #151's `loadUnbakedAssets` base-model `fromStream` used **only** to inspect overrides; #151's cache already saw 3,960 requests/3,944 hits/16 models and is a valid retained but different mechanism.

## Actual deployed version and source correspondence limit

The pinned exact-pack fixture is release `exact-pack-2026-09-02-v1`, SHA-256
`7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.
#267 previously verified its deployed CIT JAR as:

- `mods/(svfr) CitResewn {v0} [1.21.1] [MAINLOC].jar`;
- length **203,957 bytes**, SHA-256 **`315b46f2a78d5298426fb594558873ac19abbc499f77578f6cd4f98e6809e6a9`**;
- deployed namespace `schm.shsupercm.citresewn`; reported runtime mod version `0` through Sinytra Connector.

This is the real *binary identity* for the evidence below, **not** proof of a publicly corresponding source tree. #223 and #257 established that the deployed package is neither archived upstream `shcm.shsupercm.fabric.citresewn` nor the modern NeoForge fork `com.github.citresewn`. Do not silently replace/redistribute the deployed JAR or equate semantic similarity with bytecode/source identity.

Source inspected at two exact public commits as semantic references:

1. Archived upstream [SHsuperCM/CITResewn@47e7cb861db09744880c69f41f989b1904d63d7f TypeItem](https://github.com/SHsuperCM/CITResewn/blob/47e7cb861db09744880c69f41f989b1904d63d7f/defaults/src/main/java/shcm/shsupercm/fabric/citresewn/defaults/cit/types/TypeItem.java) (upstream 1.2.2 lineage). The `getModelForFirstItemType` and `getModelFromOverrideModel` implementations open current resource streams, fully deserialize models, and mutate IDs/texture data after parsing, returning `null` on caught exceptions.
2. [CancriRecoleta/CITResewn@8cca0f127f3898472e2109f6ac6a797253756893 TypeItem](https://github.com/CancriRecoleta/CITResewn/blob/8cca0f127f3898472e2109f6ac6a797253756893/src/main/java/com/github/citresewn/defaults/cit/types/TypeItem.java) (public NeoForge `0.7.22`, MC 1.21.1). Its methods retain that ordering but call `BlockModel.fromString`; in the texture path they may replace a spawn-egg template and remove `layer1`; generated IDs use a shared seen-set. The two methods `getModelForFirstItemType` / `getModelFromOverrideModel` create a fresh model every invocation. This is source-shape support, **not a claim of deployed source correspondence**.
3. [NeoForge 1.21.1 ExtendedBlockModelDeserializer](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/model/ExtendedBlockModelDeserializer.java) was read on the public 1.21.1 branch. This is a branch source reference, not a pinned exact `21.1.248` commit. `deserialize` delegates to `super.deserialize`, mutates the resulting model's `customData` (geometry/root transform/render type/visibility), and `deserializeGeometry` executes the currently registered `GeometryLoaderManager.get(name).read(originalJsonObject, context)`. Custom loader callbacks receive a mutable `JsonObject` and may mutate it.

## Valid observed workload, correctly typed

#332's *diagnostic* hosted exact-pack run [37132402178](https://github.com/wachipayox/BootOptim/actions/runs/37132402178) at diagnostic head `dc6689d3` had an unchanged selected resource-pack order (14 packs), 8192×8192×2 atlas, no BootOptim Mixin failures, complete accounting, and a main-menu reach. Origin: hosted exact-pack/Linux, first startup, endpoint `main_menu`; no physical laptop comparison. Observations:

| Original work | Calls | Full strings | Sample count | Observed sampled CPU | Observed sampled wall |
| --- | ---: | ---: | ---: | ---: | ---: |
| CIT mutable model methods (first/override) | 7,920 + 3,960 = **11,880** | **16 distinct**; 22,708 retained UTF-16 chars | 704 root samples | first 59.557248 ms / override 26.718667 ms | not exclusive |
| Their `IOUtils.toString` child | **11,880** | same full-content census | 704 | **3.043904 ms** | **6.005157 ms** |
| Their `BlockModel.fromString` child | **11,880** | same full-content census | 704 | **19.543442 ms** | **62.489079 ms** |

Sampling was expected 1/16, whitened, with available CPU clocks; failures, nulls, inflight and census truncation were zero. These CPU/wall figures are **actual sampled subsets contaminated by probe overhead**, not total parse costs, not net avoidable syntax CPU, and not a scaling factor for seconds or heap saved. The mutable method root includes content census and nested operations; root/children overlap and must **never** be summed. The 70.001-second reported menu mark is diagnostic health only. The 11,880 count proves repetition of full strings, **not** that the JSON lexing/AST construction rather than `ExtendedBlockModelDeserializer`, geometry callbacks and model construction owns most CPU.

#151's successful hosted/physical figures refer only to its **existing read-only override base cache**. They are not a transferable model-parse savings estimate.

## Candidate designs checked and reasons not to stage them

**A. Return a cached BlockModel or shallow-copy it: reject for correctness.** Each call must receive independently mutable fields, elements, overrides, texture mappings, root transforms and customData, plus fresh custom geometry and callbacks. `new BlockModel(...)` is not a proven deep clone of NeoForge extensions; caller mutates `name`, spawn-egg textures and generated-ID order, and a returned model may be inserted into current ModelBakery maps. A shared object would change identity/state across CITs and potentially reloads.

**B. Cache Gson `JsonElement` and deserialize each new model: not proven safe or net useful.** Every call must still perform the current resource-manager lookup, provider/filter callbacks, `Resource.open`, complete read, error handling and `BlockModel` + custom-loader adapter construction. A cached `JsonObject` cannot be passed as shared mutable input to an arbitrary `geometryloader.read(object, context)`; a defensive deep copy is needed on each hit. That copy is new allocation/traversal overhead before all stock adapters and customData initialization, while only JSON *syntax* decoding could be avoided. Using `GSON.fromJson(JsonElement, BlockModel.class)` instead of stock `BlockModel.fromString` may also change malformed-JSON diagnostics, reader consumption, number/token handling or adapter/error surfaces; no real-class equivalence replay or exact installed custom-loader corpus certifies otherwise. The public NeoForge source shows callbacks are not pure grammar work.

**C. Cache tokens or a custom immutable syntax IR: defer/reject for this installed JAR.** It would require a new parser/adapter bridge, new version/shape guards, extra content-key equality checks and explicit generation invalidation. The only observed parse CPU includes fresh model construction and callbacks, which must still run. The incremental *grammar-only* recoverable budget is not measured. No current evidence shows a material, reliable net CPU gain to pay for this semantic/maintenance surface.

**D. Key by resource path, manager identity or reuse across generations: reject.** Identical paths do not guarantee current content, selection, error behavior or resource/filter callbacks. A full current-content equality key would be mandatory; keying before fresh reads would skip observable operations. Reload-scoped lifetime at most, clear on each ModelBakery generation; no persistence. Even this does not establish adapter/error equivalence.

No worker/OpenGL change, model publication reordering, shortcut around custom loaders or provider callbacks is acceptable.

## Decision, gates, and reopening

**Binary decision: reject syntax-tree/model cache *as a BootOptim runtime candidate now*.** This is a **preimplementation feasibility/budget decision**: repeated content is proven, but exclusive grammar CPU available to remove is not; adapter and fresh mutable-model work must remain. The sole timed parser subset is small and *inclusive*, not a quantified total ceiling. With no certified fresh-tree/error equivalence and no demonstrated material net segment CPU budget, the cost of implementing another optional Mixin/IR exceeds the justified evidence. This **does not prove** all possible syntax reuse is slower, and is not a negative performance A/B result. No disabled switch is retained.

**Minimum useful gate before any Actions on this PR:** source/identity audit, documentation diff and links only; no executable candidate to compile or compare. The normal Build workflow may run automatically on push/PR as repository hygiene, but an exact-pack smoke/A/B, startup benchmark, or physical test would waste resources and would not measure a candidate. Accordingly **two controls/two candidates = N/A (zero collected for relay 217)**, rather than invented or borrowed C1/B1/B2/C2. No `[exact-pack-ci]` trigger is requested. This follows the segment-first policy's requirement to decide rather than park an unproven switch, without mislabeling invalid or absent measurements as a speedup or regression.

**Reopen only on a changed, measurable premise**, with all of:

1. Exact installed JAR/source/adapter identity established (including the hash above and any replacement source license), and current selected model strings/loader shapes inspected without substituting an unofficial binary;
2. a low-overhead **exclusive syntax/lexing** CPU + allocation budget in the actual CIT segment that excludes fresh model/deserializer/geometry callback work and includes candidate key lookup, validation and deep-copy overhead; do not use per-call clocks like #336 or scale #332 samples into a fictitious exact total;
3. a strict real-class test comparing stock string path and cached-tree path for all selected full strings and malformed/adversarial JSON, including exact error class/catch-to-null, independent model object graphs/customData, all callback counts/order, custom loader input mutations, synthetic-ID order, and two successive F3+T generations;
4. then an isolated, fail-open, optional-mod-compatible candidate with real activation proof, focal compile, startup, **2 controls and 2 candidates** on same origin/endpoint and comparable pack/JVM, testing **net owner CPU first**, then allocation/GC and relevant critical-path wall; if it survives, fresh SHA-locked hosted exact-pack A/B with `[exact-pack-ci]`, `exact-pack-mode: ab`, `exact-pack-repetitions: 2`, *real* candidate/control JVM properties, effective workflow inputs, artifact inspection, and physical semantic gate. Do not add a production catalog entry unless that mechanism is retained.

This report neither touches #337 parent tracking nor imports #332's diagnostic implementation. Integration owner chooses whether to merge this documentation-only rejection; no candidate cleanup is needed because no candidate was created.

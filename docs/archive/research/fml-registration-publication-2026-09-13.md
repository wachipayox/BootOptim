# FML 4.0.43 final registration publication audit — 2026-09-13

Status: **PROFILED / AT-ENGINE ROUTE ONLY**

Scope is deliberately limited to `LoadingModList.addAccessTransformers`, `addMixinConfigs`, and `addEnumExtenders` in the FML 4.0.43 source snapshot used by the exact pack. This work does not cover `validateLanguages`, `ModSorter`, `addForScanning` / `BackgroundScanHandler`, Connector/JiJ, classloader construction, GL/render work, or resource reload.

## Authority and source pin

- Integration base: `agent/integration-current` @ `b3f0c5f6462a359483883741ac16d2f154868900`.
- FML source snapshot: `neoforged/FancyModLoader@15c77cf658f360c171668a8700d02c30ad0cd965`.
- Runtime pin enforced by the diagnostic agent: named module `fml_loader@4.0.43`.
- Diagnostic PR: #274.

`ModValidator.stage2Validation` calls the three methods serially after language validation and sorting. Inclusive Stage 2 timings are therefore not relabeled as time belonging to these methods; the only attribution below comes from direct probes of the methods themselves.

## Source audit

### `addAccessTransformers`

`ModFile.identifyMods` has already parsed the mod metadata, resolved each declared AT filename to a `Path`, checked existence, and stored the ordered path list. `addAccessTransformers` therefore performs only ordered traversal of `modFiles` and each stored AT path, but every path calls `FMLLoader.addAccessTransformer`, which immediately invokes the AccessTransformer engine parser/merge path.

The FML snapshot pins AccessTransformers 10.0.1. Its per-file load is transactional but expensive structurally: it copies the cumulative transformation map, parses the next file into that copy, validates the full accumulated map, clears and republishes the whole map, and rebuilds the targeted-class set. `Transformation.mergeStates` also preserves ordered origin strings. Consequently duplicate rules/files are not semantically disposable: duplicates can affect ordered origins and the point/content of conflict diagnostics.

**Decision:** the FML collection loop itself is essentially unavoidable and tiny. Do not cache or deduplicate AT files in BootOptim. The only credible optimization route is a real AccessTransformers-engine correction that removes repeated whole-state maintenance while retaining exactly the same per-file transaction/failure boundary, merge order, origin list, final transformation map, and targeted-class set. A pre-parsed per-file immutable IR is theoretically possible, but it must reproduce parser exceptions (including file/line/origin) at the same ordered publication point; no production implementation is justified here.

If a persistent IR were ever tested, identity would minimally need AccessTransformers implementation/parser version, exact input bytes, observable origin path string, and ordered file position. Caching a *merged* result would additionally require the full ordered prefix identity, which makes it unattractive. No live parser/FML objects may be retained.

### `addMixinConfigs`

Mixin metadata is already parsed and stored during `ModFile.identifyMods`. `addMixinConfigs` walks the final sorted mod-file list, evaluates each config's `requiredMods` against `LoadingModList.fileById`, and appends `(config, modId)` to `DeferredMixinConfigRegistration` in encounter order. The two-argument queue call does not parse the Mixin JSON.

The deferred registrar has a one-way `added` state: queueing after registration throws. Later `registerConfigs()` calls `Mixins.addConfiguration` in queue order, resolves the registered configs, decorates them with mod IDs, and clears the queue. Moving actual Mixin registration earlier would cross the intended deferred lifecycle/classloader boundary; deduplicating queue entries could also change registration/decorating order and duplicate behavior.

**Decision:** no BootOptim optimization. The final `requiredMods` decision and ordered queue publication belong after the final mod list exists, and their measured cost is small. There is no safe identity/versioned cache worth introducing.

### `addEnumExtenders`

`addEnumExtenders` walks final mod infos in loading order, reads each `enumExtensions` declaration, resolves the resource, checks `Files.isRegularFile`, emits the stock loading issue for a missing file, builds the map, and then publishes through `RuntimeEnumExtender.loadEnumPrototypes`.

Each `EnumPrototype.load` opens and parses JSON and emits loading issues in encounter order for malformed or invalid entries. `loadEnumPrototypes` then sorts prototypes, groups them by target enum, detects duplicate field names, emits duplicate issues with affected mods, drops errored enum groups, and publishes the static prototype map used later by the enum class processor. Prototype order is observable through generated enum entry/ordinal order.

**Decision:** no BootOptim optimization. A hypothetical preparse would need an immutable representation plus exact replay of every issue in the same order and final-mod ownership context; only three files exist in the exact pack and the entire method is tens of milliseconds hosted. Deduplicating enum files/prototypes is invalid because duplicate detection is itself stock behavior.

## Hosted exact-pack probe

The diagnostic javaagent is default-off, bootstraps a JDK-only recorder, pins `fml_loader@4.0.43`, stores only primitives/strings, and leaves stock return values, exceptions, ordering, and objects untouched. It instruments the three requested top-level methods and direct per-item publication/parse calls. No live `ModFile`, `IModInfo`, `Path`, map, parser object, classloader, or callback object is retained.

Two exact-pack startups reached a usable main menu with the expected 14/14 resource-pack selection in order, `reload_count=1`, 8192x8192 block atlas at two mip levels, and zero BootOptim Mixin errors. The post-run analyzer initially rejected both because an optional inner Advice for `RuntimeEnumExtender.loadEnumPrototypes` did not bind; the parent `addEnumExtenders` and all three `EnumPrototype.load` calls were observed. The analyzer was then made conservative: it reports a combined enum residual instead of inventing a finer split.

| Hosted probe | `addAccessTransformers` | 97 per-file AT calls | `addMixinConfigs` | 322 queue calls | `addEnumExtenders` | 3 enum JSON loads | Top-three sum |
|---|---:|---:|---:|---:|---:|---:|---:|
| run 1 | 275.104 ms | 272.654 ms | 10.983 ms | 1.080 ms | 19.559 ms | 2.064 ms | 305.646 ms |
| run 2 | 350.664 ms | 347.437 ms | 13.602 ms | 1.143 ms | 23.030 ms | 2.672 ms | 387.297 ms |

Derived serial residuals are ~2.45/3.23 ms outside the per-file AT engine calls, ~9.90/12.46 ms outside the actual Mixin queue calls, and ~17.49/20.36 ms in enum config/path collection plus sort/group/duplicate/publication outside the three JSON-file loads. These are inclusive diagnostic timings inside the named methods, not TTMM savings. In particular they do **not** justify assigning PR #271's hosted or physical Stage 2 residual to these subphases.

The first per-file AT call is much larger than later calls in these instrumented launches, so it is not interpreted as pure parser cost; classloading/JIT and other one-time effects are included. The stable conclusion is structural and relative: almost all measured `addAccessTransformers` wall/CPU is below the FML traversal in the per-file AccessTransformers engine call.

## Ordering, failures, generations, and fallback

These registrations are startup-generation state, not resource-reload state. The AT engine accumulates rules in ordered file calls; the Mixin queue has a one-way queue-to-registered transition and is then cleared; enum prototypes are published once for class transformation. A future cache must therefore be tied to exact startup inputs/versions and must never reuse live objects across runs or reload generations.

The diagnostic agent fails open operationally: absent the opt-in property it does nothing; an FML module/version mismatch disables recording rather than changing the loader; probe exceptions are swallowed while stock exceptions propagate. A future AccessTransformers experiment must add an AT 10.0.1 pin and fall back to the stock engine/path loop on any version/input mismatch.

For an AT-engine correction, equivalence requires more than a successful menu: compare the final target/modifier/finality map and ordered origin lists; run adversarial malformed syntax and conflicting-rule fixtures proving the same failing file, line, exception/loading issue/log ordering; and compare transformed class output for affected classes. Mixin and enum publication order must remain byte/sequence-equivalent where observable.

## Promotion gate

No production change is proposed by #274. If an AccessTransformers-engine correction is implemented in a later isolated experiment, hosted exact-pack must preserve resource selection 14/14 in order, `reload_count=1`, atlas dimensions/mips, zero Mixin errors, and stock failure fixtures before any performance claim. End-to-end A/B must then demonstrate critical-path benefit; direct method timing is not sufficient.

Before production, physical/in-world validation is still required: visually usable title screen, create/load an integrated-server world, exercise mod features that rely on transformed access and Mixins, exercise every extended enum/ordinal/network-check path represented by the pack, save/re-enter the world, and verify no gameplay or rendering difference. This research has **sin evidencia física**.

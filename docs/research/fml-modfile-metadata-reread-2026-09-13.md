# FML ModFile metadata reread attribution — 2026-09-13

Status: **profiled; duplicate standard route proven, hosted ceiling too small for a production candidate; hardware-sensitive reopen retained**.

Authority: `agent/integration-current` at `b3f0c5f6462a359483883741ac16d2f154868900`. Diagnostic PR: #272. Validated diagnostic commit: `5727bf4fe40a5e590e9563303602ebb675c8e965`.

## Question

Does FML 4.0.43 actually execute the standard `neoforge.mods.toml` metadata parser twice within one `ModFile` lifetime — once during `ModFile` construction and again from stage-1 `identifyMods()` through `ModFileParser.readModList` — and is that second route large enough to justify a future intra-`ModFile` reuse experiment?

This experiment changes no metadata result, parser state, ordering, lifecycle, coremod/mixin/access-transformer behavior, graph, resources, rendering or gameplay. It implements no cache/reuse. The javaagent is default-off and fails closed unless the named module is exactly `fml_loader@4.0.43`. It retains only strings/primitives plus parser class and `System.identityHashCode`, never parser/ModFile/filesystem/handle/layer/classloader objects.

## Source-pinned runtime shape

Pinned FML source is `neoforged/FancyModLoader@15c77cf658f360c171668a8700d02c30ad0cd965`.

`ModFile` stores a `ModFileInfoParser`; its constructor invokes `ModFileParser.readModList(this, parser)`, and stage-1 `identifyMods()` invokes `ModFileParser.readModList(this, parser)` again. The standard reader supplies `ModFileParser::modsTomlParser`. That path performs `FileConfig.load()` and then `copyConfig`, where the loaded config is serialized with the TOML writer, reparsed with the TOML parser and made unmodifiable before construction of fresh `NightConfigWrapper` / `ModFileInfo` state.

The production persistent scan cache does not cache this metadata. Its `CachingModFileReader` wraps the same standard `ModFileParser::modsTomlParser`; this is why the exact-pack parser runtime type is primarily a BootOptim lambda while the nested standard parser boundary remains observable.

## Hosted exact-pack evidence

Valid run: https://github.com/wachipayox/BootOptim/actions/runs/34750076909

Artifact: https://github.com/wachipayox/BootOptim/actions/runs/34750076909/artifacts/10315508202

The exact-pack smoke reached the main menu at 82,431 ms. This is a diagnostic run, not an A/B result. Contract gates were valid: 0 BootOptim Mixin errors, resources 14/14 in expected order, `reload_count=1`, blocks atlas 8192x8192 with 2 levels.

All attribution below is from the instrumented single-thread route. `union wall` is an interval union and therefore non-overlapping at each stated level; thread CPU comes from `ThreadMXBean.currentThreadCpuTime` where available. Instrumentation overhead means these component values are attribution values, not stock savings.

| Scope | Calls | Union wall ms | Thread CPU ms |
|---|---:|---:|---:|
| `stage1Validation` | 1 | 69.524 | 69.338 |
| stage-1 `readModList` | 255 | 57.730 | 57.579 |
| coremod checks | 255 | 3.190 | 3.175 |
| mixin checks | 255 | 2.000 | 2.013 |
| access-transformer metadata checks | 255 | 0.969 | 0.973 |
| stage-1 residual after named children | — | 5.635 | — |
| standard parser, initial construction | 359 | 220.250 | 217.012 |
| initial `FileConfig.load` | 359 | 77.920 | 77.006 |
| initial immutable write→reparse | 359 | 57.043 | 56.070 |
| standard parser, stage 1 | 242 | 56.714 | 56.610 |
| stage-1 `FileConfig.load` | 242 | 25.631 | 25.589 |
| stage-1 immutable write→reparse | 242 | 15.401 | 15.421 |
| standard stage-1 parser residual | — | 15.682 | — |

The standard path is therefore **really repeated** for 242 stage-1 calls. For the dominant exact-pack standard parser wrapper, `CachingModFileReader` has 358 initial calls and 241 stage-1 calls with one parser identity shared across both phases. The direct stock `JarModsDotTomlModFileReader` standard parser adds 1 initial + 1 stage-1 call with one shared identity. Other parser types remain separate and are not eligible for any inference about standard TOML reuse.

The direct `ModFile.identifyMods` advice produced no events in this launch shape, matching the earlier #271 observation, so it is deliberately not used as a validity boundary. The required stage-1 parser and helper scopes are instead directly bounded by `ModValidator.stage1Validation`; the analyzer fails if either initial/stage-1 `readModList` route is absent, the standard parser lacks a shared identity across phases, any standard parser call lacks exactly one `FileConfig.load` plus one immutable-copy reparse, FML is not 4.0.43, or an instrumented stock boundary throws.

No instrumented boundary threw. No metadata fallback was introduced. The exact-pack log showed no BootOptim Mixin errors. The access-transformer helper number intentionally covers metadata extraction only; later per-path `Files.exists/notExists` probes remain in the stage-1 residual.

## Decision

The duplicate path is proven, but **do not implement reuse now**. The authoritative stock-ish measurement from #271 put the entire `stage1Validation` at only 99.006 ms wall / 93.738 ms thread CPU. Therefore even perfect removal of every repeated metadata operation cannot establish more than a sub-100-ms hosted ceiling, before preserving fresh object construction, validation, error surfaces and other stage-1 work. Relative to a roughly 80–100 s hosted menu path, this does not justify a production lifecycle optimization or an A/B candidate.

This is **sin evidencia física**. `FileConfig.load` is storage-sensitive, so the front is not permanently closed for a materially slower physical storage path. Reopen only if a comparable physical attribution shows stage-1 standard metadata reread/reparse itself is material on the critical path; do not reopen from parser counts alone or from the inclusive instrumented wall above.

## Strict semantics if reopened

A future experiment must be restricted to the exact standard `ModFileParser::modsTomlParser` lineage under a version-pinned runtime and must fail open for custom/arbitrary parsers. It must not reuse the prior `IModFileInfo`, `ModFileInfo`, `ModInfo` or `NightConfigWrapper` object graph: those objects are rebuilt, carry file back-references, run validation and publish properties, so object identity/side effects/failure points are part of stock semantics.

At most, after proving the source is immutable for the interval, a future candidate may retain an immutable TOML parse payload inside the same `ModFile` lifetime, then construct fresh wrappers/info objects and rerun stock validation/property publication and all coremod/mixin/access-transformer checks in stock order. Mutable directory/dev inputs, source changes, parser mismatch, unexpected state, decode/validation failure, or inability to reproduce the second-read error surface must execute the stock second read. No persistent metadata cache is authorized.

No TTMM gain is claimed by this diagnostic PR.

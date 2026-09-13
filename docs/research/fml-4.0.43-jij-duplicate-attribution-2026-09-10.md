# FML 4.0.43 `jij:` duplicate attribution — 2026-09-10

Diagnostic branch: `agent113/jij-duplicate-attribution-20260910`, based on integration `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Result

Hosted exact-pack run 34514270141 is the valid diagnostic gate. It reached `main_menu` at 91,847 ms; dependency discovery was 7,607.532 ms inclusive. Resource selection matched the fixture and reload count was 1. These are single-run diagnostic measurements, not an A/B speedup claim.

JarJar `scanMods` occupied 2,601.275 ms union wall across two calls (34.19% of dependency discovery). The first call came from stock `ModDiscoverer.discoverMods`; the second came from `ConnectorLocator.loadEmbeddedJars` and then the same stock discovery path.

`loadModFileFrom(3)` ran 308 times for 2,443.254 ms union wall. Post-process strong identity `(parent SHA-256, relative embedded path, child SHA-256)` collapses those to 169 identities. Exactly 139 identities repeat once, all within the same JarJar scan: 138 have the phase pattern `recursive_detection -> selected_materialization`; one has `recursive_detection -> recursive_detection`. Thus the 308 calls are explained as 170 recursive-detection materializations plus 138 final selected materializations.

The sole recursive-only repeat is:

- parent SHA-256 `a086b2157458e8e1214232a52a2a92924481fd3fd6620a61a4f23373b557e791`
- relative path `META-INF/jarjar/conditional-mixin-neoforge-0.6.3.jar`
- child SHA-256 `a990b68c40161596fd9cebb98a715b11882aa55be9391ff543dfbf1ede594560`
- 28,549 bytes
- loads 1.775 ms and 1.149 ms, both inside recursive detection.

The artifact `agent113-jij-duplicate-profile` contains `jij-profile-summary.json`, whose `repeated_identities` array gives all 139 complete strong identities and both occurrence timings.

## Non-inclusive wall attribution

The measured top-level boundaries are deliberately unioned rather than summed with their inclusive parents.

| Boundary | Union wall |
| --- | ---: |
| `loadModFileFrom(3)` | 2,443.254 ms |
| ↳ direct `jij:` `newFileSystem` | 4.411 ms |
| ↳ load residual after FS open | 2,438.843 ms |
| descriptor resource callback/open | 8.737 ms |
| descriptor `MetadataIOHandler.fromStream` parse/read | 16.374 ms |
| identify callback | 1.425 ms |
| JarJar scan residual outside measured top-level work | 131.484 ms |

The load residual contains `findResource`, URI/env construction, `JarContents.of`, `DiscoveryPipeline.readModFile`, and probe overhead. The private `DiscoveryPipeline.readModFile` method did not accept the ByteBuddy hook in the first run, so it is not falsely reported as a separate metric. The requested JarSelector callback boundaries are still complete: source-producer (`loadModFileFrom`) 308 calls, resource-reader 332 calls (46 present descriptor streams), identification 300 calls, failure callback 0 calls. Descriptor parse ran 46 times.

Phase attribution: recursive detection is 170 loads / 1,588.702 ms union; selected materialization is 138 loads / 854.552 ms union. Direct filesystem-provider opening is only 4.411 ms, so the material residual is not explained by provider `newFileSystem` itself.

## Causal source mechanism

FML 4.x supplies `JarSelector.detectAndSelect` with `loadModFileFrom` as its source producer. JarSelector calls that source producer during `recursivelyDetectContainedJars` so a child can itself be inspected for nested JarJar metadata. After version selection, `detectAndSelect` invokes the source producer again to obtain the selected child returned to FML. FML's source producer creates a fresh `jij:` filesystem, builds `JarContents`, and calls the discovery pipeline reader.

The runtime phase pattern (138 recursive -> selected strong-identity repeats) directly matches that two-stage contract. The remaining one repeat is a tiny duplicate encountered twice during recursive detection, not a material second hotspot.

## Semantic limits and decision

No live `FileSystem`, `JarContents`, `SecureJar`, `IModFile`, descriptor stream, or reader object is retained by the profiler. Strong hashes and sizes are reconstructed after process exit. No OpenGL/render work is moved and no gameplay code is changed.

**NO-GO for production duplicate-collapse on the current FML 4.0.43/JarJar contract.** Reusing the recursive `IModFile` for the selected result would necessarily reuse the live filesystem/JarContents lifecycle forbidden by the experiment and could change reader state, parent discovery attributes, callback/failure order, or lifetime. Omitting either source-producer invocation would skip a stock callback. The one recursive-only duplicate is ~2.9 ms total and is not a promotion candidate.

A fail-open route can be reopened only behind a version-pinned upstream/FML contract that explicitly separates a detached, immutable *probe/selection record* from final materialization. The probe may retain only value data (metadata/coordinates/relative path), not live filesystem/JarContents/SecureJar handles. Final commit must still create fresh stock objects and execute every reader/identify/error callback in stock order; any unsupported version or mismatch falls back to current stock `detectAndSelect`. Under that constraint the present evidence does not establish a material removable wall, because the expensive 2.439 s residual includes the stock materialization/reader path that must still run.

Next decision: do not promote or implement a BootOptim cache. Reopen only if a separately instrumented, version-pinned `JarContents.of` vs `DiscoveryPipeline.readModFile` split proves a large detachable pure subphase *and* an upstream callback-equivalent probe API can preserve the lifecycle above. Otherwise close this line as semantically structural JarJar work.

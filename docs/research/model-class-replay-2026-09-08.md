# BlockModel class-replay headless boundary — 2026-09-08

Status: **BLOCKED AT REAL PARENT RESOLUTION / TOOLING-ONLY / NO RUNTIME CHANGE**

Base authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This investigation was the class-level reopening requested by #181. It did **not** produce a trustworthy full parent/texture/digest replay, and this document does not claim one.

## Historical guardrails

The work does not reopen the rejected #36/#66/#152/#153/#170/#171/#177 families. #178 remains a fixture/dependency graph laboratory, not a Minecraft semantic oracle. #66's 5,448/5,448 equality result was still slower (`189.190 ms` candidate vs `179.709 ms` stock), so another reload-local IR without class-level evidence remains a no-go.

The requested `docs/research/next-optimization-campaign-2026-09-08.md` is absent at the authoritative integration SHA and was not found in the visible #178–#182 changes. Its contents were not invented.

## What real code runs headlessly

`modelReplaySelfTest`, supplied only through `tools/model-class-replay/model-replay.init.gradle`, compiles against the project's real NeoForge/Minecraft runtime and executes without a graphical client:

- `BlockModel.fromStream(Reader)`;
- real `BlockElement` and `BlockElementFace` objects;
- `BlockModel#getMaterial` for a local texture reference;
- NeoForge `ElementsModel(List<BlockElement>)` construction.

The controlled model contains GUI light, display transform, ordinary geometry, face texture reference, cull and tint data. The test also confirms that a deliberately wrong texture reference differs from the observed real one. This is only a **pre-parent smoke boundary**, not an equivalence digest for resolved models.

## Exact blocking API

A clean Gradle `JavaExec` cannot execute trustworthy stock `BlockModel.resolveParents(Function)` by merely placing Minecraft/NeoForge classes on the classpath.

Observed sequence in GitHub Actions:

1. `BlockModel.resolveParents` reaches `ModelBakery` and therefore Minecraft registry/bootstrap state.
2. Without bootstrap, the runtime fails with `IllegalArgumentException: Not bootstrapped` from `BuiltInRegistries`.
3. Attempting the normal `net.minecraft.server.Bootstrap.bootStrap()` under NeoForge 21.1.248 reaches `net.neoforged.neoforge.common.util.flag.FeatureFlagLoader.loadModdedFlags()`.
4. There `LoadingModList.get()` is null because FML/ModLauncher has not established the loading state, producing the reproducible `NullPointerException` boundary.

`modelReplayBootstrapBoundary` is green only when that exact `FeatureFlagLoader` / `LoadingModList` condition is reproduced. The harness deliberately does **not** fabricate `LoadingModList`, replace `resolveParents`, or substitute a custom parent parser.

An earlier controlled cycle probe also showed that after stock reports a parent loop, continuing the intentionally invalid cyclic model into NeoForge `getElements()` can recurse through `BlockGeometryBakingContext#getCustomGeometry`. Cycles therefore belong to the real parent-resolution/error boundary, not to a fake downstream geometry continuation.

## Fixture/provenance preparation

`tools/model-class-replay/prepare_fixture.py` remains useful preparation for the next step. It targets the public exact-pack contract from `exact-pack-ci.md`:

- tag `exact-pack-2026-09-02-v1`;
- asset `bootoptim-exact-pack.zip`;
- SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

It reads selected external `file/...` packs from `options.txt`, records winners and shadowed candidates, materializes a deterministic raw-parent byte closure, and explicitly marks standalone mod-to-mod precedence as weak (`bounded_helper_mod_filename_order`). Its JSON parsing is enumeration/provenance only and is outside any Minecraft algorithm claim.

Python unit tests cover selected-pack precedence, disabled-pack exclusion, shadow provenance and parent-byte closure.

## Why the requested digest/metrics are not claimed yet

The draft replay code sketches phase timing and a canonical structural digest, but it is not a valid stock oracle until real parent resolution runs inside FML state. Therefore this PR does **not** publish or claim:

- stock/identity parent-equivalence SHA;
- parent-chain/fallback equivalence on the exact pack;
- phase wall/CPU/allocation results for resolved parents;
- final material-chain equivalence;
- sprite, `FaceBakery`, `BakedQuad`, render-bucket, callback or GL equivalence;
- TTMM or physical-laptop improvement.

Presenting those fields from a custom substitute would violate the purpose of this investigation.

## Exact next frontier

Run the same replay entrypoint inside a real FML/ModLauncher lifecycle. Preferred order:

1. identify the lightest NeoForge Gradle test/data lifecycle that initializes `LoadingModList` and registries without renderer/GL;
2. execute `BlockModel.resolveParents(Function)` there using the provenance-preserving bounded fixture;
3. only after that passes, restore the stock/stock/identity canonical digest and deliberate-regression diff gate;
4. if no non-graphical lifecycle supplies the required client model state, move to a minimal instrumented exact-pack client smoke that exits immediately after the ModelManager pre-sprite boundary.

That next run should then measure exclusive wall/current-thread CPU/allocation scopes for parse, parent resolution, texture-chain/material lookup and ordinary lowering. Only a new material class-level cost outside the already rejected families would justify a candidate optimization.

No physical laptop test is requested by this work.

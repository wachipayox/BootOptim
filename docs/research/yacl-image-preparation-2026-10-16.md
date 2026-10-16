# YACL image preparation off the calling thread — 2026-10-16

Status: **REJECTED 2026-10-17** (documented on integration; PR #389 closed, not merged). Reopen criteria below.

Origin: RRLS experiment `compat.YaclPreparationMixin` (commit `18a5615`, 2026-09-26), which
dispatched YACL's image `prepare` on the preparation executor behind an opt-in marker. Physical
run of RRLS `79a3433` (2026-09-26, dispatch enabled): synchronous request return of 98 ms and 96 ms for
the two manual reloads; final rendered-frame gaps stayed at 5.7 s and 6.1 s. The stock comparison is
an earlier observer-only run (RRLS `04723d3`, 2026-09-26 14:02, dispatch off) with 602 ms and 380 ms
synchronous returns, so the variance between runs is large and one pair of runs is not a baseline.
See the RRLS archive note
(`docs/archive/research/rrls-fancymenu-early-menu-2026-09-24.md`, BootOptim) for the full session log.

This port is **not** a startup optimization. It only changes manual reloads after the game has loaded
(F3+T, resource-pack reloads). Initial reload and startup-to-menu are unchanged by design.

## Target

- Mod: Yet Another Config Lib v3, `yet_another_config_lib_v3`, `3.8.2+1.21.1-neoforge`
  (Modrinth, SHA-1 `fe270c9f6e0a8142cf316944087f5109cef08bbb`, 1,118,651 bytes).
- Class: `dev.isxander.yacl3.gui.image.YACLImageReloadListener`.
- Call site: `reload0` calls `prepare(ResourceManager, Executor)` synchronously, then
  `preparationBarrier.wait`, then `apply(suppliers, gameExecutor)`.
- No exact-version gate. `@Pseudo`, `remap = false`, `require = 0`. Kill switch
  `-Dboot_optim.yaclImagePreparation=false`.

## What `prepare` does (decompiled from 3.8.2 with Vineflower 1.10.1)

Synchronous prefix, on the calling thread (the render thread for manual reloads in vanilla):

1. `manager.listResources("textures", predicate)`: directory/pack enumeration of texture resources.
2. For each location: stream over `PRELOADED_IMAGE_FACTORIES` (GIF and WebP, only when
   `preloadComplexImageFormats` is set), `factory.apply(location)`, which only builds a factory object.
   `AnimatedDynamicTextureImage.createGIFFromTexture` and `createWEBPFromTexture` return a lambda that
   captures the location. They do no I/O.
3. Each location is submitted with `CompletableFuture.supplyAsync(..., executor)`.

Asynchronous part, already on the `executor` passed to `prepare` (the background executor):

4. `ImageRendererManager.safelyPrepareFactory` calls `factory.prepareImage()`. For the preloaded
   factories this reads the resource and decodes frames into a `NativeImage`. No GL, no
   `TextureManager`, no `RenderSystem`.

Apply, on the game executor (unchanged):

5. `apply` runs `ImageSupplier.completeImage()` on the game executor. This is the step that creates
   `AnimatedDynamicTextureImage` and its `DynamicTexture`, and calls `TextureManager.register`, so it
   is GL/render-thread work. It stays where it is.

Checked for GL and render-thread calls: `RenderSystem` appears in `DynamicTextureImage`'s constructor
(`assertOnRenderThread`) and in `ImageRendererManager.completeImageFactory`. Both are on the apply
path (step 5), not in the moved prefix. `textureManager.register` is in the constructor too, so it
is also apply-only. `ResourceTextureImage.createFactory` is `OnThread` and is not used by the preloaded
formats.

## Why the moved prefix is safe to run on the background executor

- It only reads the resource manager and creates objects. The resource manager is read-only during
  reload and is used off-thread by vanilla listeners.
- The barrier and apply still receive the same future shape: `prepare`'s future is what `reload0`
  composes with. The wrapper returns `supplyAsync(prepare, executor).thenCompose(identity)`, so
  `preparationBarrier.wait` and the game-executor apply see the same results.
- Image decoding was already off-thread; only the enumeration moves.

Residual risks, not claimed as proven:

- **Class initialization thread.** Referencing `AnimatedDynamicTextureImage` initializes its
  superclass `DynamicTextureImage`, whose static field reads `Minecraft.getInstance().getTextureManager()`.
  That is a field read, not GL. In stock code the first access happens on the render thread; the port
  may trigger it on the background thread. The JVM class-init lock makes this safe, and the read
  does not touch GL.
- **`ImageRendererManager` static init** reads `YACLConfig` on first access. The port may trigger it
  on the background thread. Config loading is file I/O and Gson, with no render-thread requirement.
- **Resource manager contention.** `Deduplicator.deduplicate` in Palladium is `synchronized` (see the
  RRLS archive). The enumeration now competes with other background workers. The ~98 ms saved on the
  render thread may be partly spent as waiting on that lock. Measure, do not assume.
- **Exception timing.** A stock failure in the synchronous prefix throws from `reload0`. The port
  surfaces it through the future. The wrapper falls back to a stock call on the calling thread, so the
  reload fails the same way, but later and through the future.
- **Algorithm drift.** A YACL version that puts GL work into `prepare` would not be detected cheaply.
  `IllegalStateException` from `assertOnRenderThread` on the background thread is caught by the
  fallback. Silent GL work off-thread would not be caught. The check is therefore a runtime fail-open
  for exceptions plus `CompatInjectionCheck` for changed call sites, not a proof for future YACL.

## Runtime protection

- `YaclImagePreparation.shouldDispatch()`: kill switch, one-way disable flag, and
  `Minecraft.isGameLoadFinished()`. Initial reload and all calls before game load stay stock.
- `dispatch(...)`: on failure of the dispatched call, `CompatLog.fallback` logs
  `[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=yacl-image-preparation mod=yet_another_config_lib_v3@3.8.2+1.21.1-neoforge reason=dispatched_prepare_failed ...`,
  disables the mechanism for the rest of the session, and calls stock `prepare` on the current thread.
- `CompatLog.active` logs once after the first dispatched call completes:
  `[WBOMBO-COMPAT] ACTIVE ...`.
- `CompatInjectionCheck.MOD_BY_PACKAGE` maps `dev.isxander.yacl3.` to `yet_another_config_lib_v3`, so a
  missing injector target after a YACL update is reported as a FALLBACK.

## Validation status

| Gate | Status |
|---|---|
| Source decompile, GL/RenderSystem review | Done (3.8.2 bytecode, this note) |
| Hosted build and startup to menu (`exact-pack-startup-benchmark`, paired) | Pending |
| Hosted A/B | **Will not show the mechanism.** The hosted harness measures startup only; this mechanism is off until a manual reload. Expect `ACTIVE` absent and no startup change, and read that as "no regression at startup", not as a result |
| Physical manual reload: `ACTIVE` line, synchronous `reload0` return time, final frame gaps, F3+T | Pending (needs the PC or laptop with a manual reload) |

No time saving is claimed. The expected effect is a shorter synchronous `reload0` return on the render
thread during manual reloads, based on the RRLS measurement of 98 ms and 96 ms.

## Physical A/B result (2026-10-17, PC, manual reload)

Setup: instance with the September resource packs, `-Drrls.diagnostics=true`, RRLS test jar with
`rrls-auto-manual-reload.enabled = exit` (reload 10 s after title, then exit). Candidate = PR #389 build
artifact (`bootstrap` jar, nested `boot_optim-0.1.0.jar`). Control = same jar with
`-Dboot_optim.yaclImagePreparation=false`. One warm-up (control), then three control/candidate pairs,
alternating order. Run logs are outside git (`C:\BootOptimBench\local\yacl-ab-20261017
uns`).

| Run | Variant | Manual reload wall (request -> done, s) |
|---|---|---|
| warm-up | control | 15.04 |
| p1 | control / candidate | 14.78 / 14.91 |
| p2 | candidate / control | 15.27 / 14.94 |
| p3 | control / candidate | 14.95 / 15.25 |

Mean: control 14.89 s (n=3 + warm-up excluded), candidate 15.14 s (n=3). The candidate was not faster.

Mechanism markers: candidate runs logged `[WBOMBO-COMPAT] ACTIVE mechanism=yacl-image-preparation`
(all three); control runs did not. No `FALLBACK` line in any run. Every reload finished with
`failed=false`.

What this does not show:

- The metric above is the whole reload wall time, not the render-thread time this port targets. The
  port's claim is a shorter synchronous return of `reload0` (98 ms in RRLS). That return time is not
  logged by the current build, so this run cannot confirm or reject it.
- `rrls-menu-freezes.log` samples taken on the render thread never showed `YACLImageReloadListener`
  in any run (0 of 7). The sampler period is probably longer than a ~100 ms prefix, so absence is not
  evidence of absence.

Decision: **not integrated.** The run did not show a benefit, and the metric that would show it is not
measured yet. Next step before any decision: add a timestamp around the `reload0` call (before and
after the prefix dispatch) behind the diagnostics flag, and repeat the paired run. If the synchronous
return does not drop, reject this with that evidence.

## Decision

Rejected 2026-10-17. The physical A/B showed no reload-wall benefit, and the metric the port targets
(synchronous return of `reload0`) was not measured. The code stays in PR #389 (closed, not merged).
RRLS keeps the experimental mixin; its retirement PR #12 is closed as well.

## Reopen or reject criteria

- Reject if a YACL update, or the physical run, shows GL or `RenderSystem` work in `prepare`.
- Reject if the physical run shows no reduction in the render-thread synchronous return.
- Reopen with new evidence if manual reloads show a render-thread stall that the synchronous prefix
  explains.

# Agent 94 — Bootstrap double-transform causal attribution (2026-09-10)

Status: **ATTRIBUTION VALID; NO OPTIMIZATION CLAIM.** Final hosted exact-pack gate `34421130525` on code head `f10237dfd27dd38b0a8c38d0670488f6e1441e0f` is green through exact replacement preflight, real main-menu reach, resource-selection validation, repeated-target ModLauncher parsing, and Mixin/Main causal partitioning.

Authority remains `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`. This diagnostic branch is `agent94/modlauncher-post-accept-audit-20260909` / PR #228. Nothing here modifies `main` or integration, and none of the diagnostic forks are production recommendations.

The requested `docs/research/boot-pipeline-program-2026-09-08.md` is absent at the exact integration SHA; its contents were not reconstructed.

## Exact pins and executable replacement contract

| component | exact pin / effective hosted evidence |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 |
| FancyModLoader | 4.0.43 effective launch contract |
| ModDevGradle | 2.0.144 in BootOptim build tooling |
| ModLauncher | `cpw.mods:modlauncher:11.0.5`, upstream `901c6ea849ae21ee7d464cd97113e77a6101a734` |
| `ClassTransformer.java` | blob `a0451dff688b78f075d0e79c3fba540361ba3304` |
| `TransformingClassLoader.java` | blob `89343a57fdc88a4c1cfea7b33e9962d081662257` |
| Mixin | `net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7`, upstream `023e39334850e839c283be413257bf459f40a5d6` |
| SecureJarHandler | **3.0.8 effective resolved artifact**, stock and not forked |
| BootstrapLauncher | 2.0.2 |
| hosted compile/runtime | Temurin 21.0.12+1 compile; Oracle 25.0.4 runtime; Ubuntu 22.04, Xvfb/llvmpipe, `ActiveProcessorCount=4` |
| exact pack | `exact-pack-2026-09-02-v1`, SHA256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`, 160 mod jars |
| MCEF fixture | Java CEF commit `a78e832f9f13c2c688caea3d04d8b84fcd238d94` |

Upstream ModLauncher is LGPL-3.0; upstream Mixin is MIT. The branch does not vendor either upstream tree. Build helpers clone exact public commits, verify exact source blobs where the probe is injected, apply a narrow diagnostic patch, and stage the result under the **same GAV** as the stock artifact.

The hosted harness uses Gradle `exclusiveContent` repositories scoped exactly to `cpw.mods:modlauncher` and `net.fabricmc:sponge-mixin`. It does not append a second launcher or Mixin jar. A diagnostic Gradle resolution task runs before the timed Minecraft process and fails if physical core identity, GAV provenance, or built SHA do not match.

Final preflight in run `34421130525` reports exactly `{mixin: 1, modlauncher: 1, securejarhandler: 1}`. Resolved diagnostic SHA256 values are:

- ModLauncher fork: `6d65a2827cab304171a286132a75e04d5947a48757d0ef5917c07a349eeb8a1b`.
- Mixin fork: `c248457d43a05891f0598a8c9c4bfb2667632862a385f371ae473cee54711941`.
- stock SecureJarHandler 3.0.8: `945c63d6deafc821616b0380c23867d9b8c2852438f8a6df72732ab933fc587d`.

The live ModLauncher identity is module `cpw.mods.modlauncher`, named `true`, loaded by `cpw.mods.cl.ModuleClassLoader@210366b4`, with CodeSource pointing to the staged same-GAV diagnostic jar. Thus the executable gate proves replacement rather than coexistence. The earlier source-level ModLauncher checkout declares SJH 3.0.4, but the **effective NeoForge 21.1.248 launch graph selects 3.0.8**; 3.0.4 must not be reported as the hosted runtime version.

## Diagnostic boundaries and semantic preservation

The ModLauncher probe is enabled only by `-Dboot_optim.modlauncherForkTrace=true` and is target-gated to `net.minecraft.server.Bootstrap`. It records each `TransformingClassLoader.maybeTransformClassBytes` request, request reason/origin, current loader/TCCL identity, bounded caller stack, and the existing `ClassTransformer` stages: plugin selection, plugins BEFORE, applied transformation-service transformers, plugins AFTER, writer construction, `ClassNode.accept`, `toByteArray`, and transform return.

The strict BootOptim boundary retains the PR #207 semantic point: transform acceptance is emitted immediately before the target transformer mutates `Bootstrap`; Bootstrap entry is injected at the first executable instruction of `bootStrap()V`. The target matcher is exact and fails closed on drift.

The Mixin fork adds only monotonic lifecycle markers around existing exact-version `MixinProcessor`/config callbacks and `applyMixins`; the latter uses an existing `finally` path so exceptions/returns preserve the stock lock and call structure. `Main` markers surround the existing Bootstrap-worker submission/execution. No executor is wrapped, no work is moved, no future/barrier is changed, no TCCL/module publication is altered, and no GL/render/gameplay path is touched.

## Causal result: two Bootstrap transform requests

The old #207 `transform_accept -> Bootstrap entry` interval is not one continuous post-transform suffix. Final run `34421130525` observes exactly two target requests through the **same** `TransformingClassLoader` and TCCL (`loader_id=1627112269`, loader `TRANSFORMER`, target module `minecraft`).

Request 1 is on `main`: `origin=securejar_get_maybe_transformed_bytes`, `raw_context=mixin`, `effective_reason=mixin`, caller `org.spongepowered.asm.launch.MixinLaunchPluginLegacy#getClassNode`. Its bounded stack continues through `ClassInfo.forName -> MixinInfo.readTargetClasses/parseTargets -> MixinConfig.prepareMixins/prepare -> MixinProcessor.prepareConfigs/select/checkSelect/applyMixins`. This is a launch-plugin transformed-byte metadata request. SecureJarHandler returns transformed bytes here but does **not** define the class.

Request 2 is on `pool-8-thread-1`: `origin=securejar_reader_to_class`, `raw_context=null`, `effective_reason=classloading`, caller `net.minecraft.client.main.Main#lambda$main$0`. Its stack continues through `ModuleClassLoader.readerToClass -> loadFromModule -> findClass -> loadClass`. This is the actual JVM class-loading/definition path.

Therefore the following alternatives are rejected for the exact pinned run: two TransformingClassLoaders, two JVM definitions/redefinition, an ordinary resource read, and a computing-frames side-load for request 1. The confirmed topology is **Mixin metadata request first; actual SJH class load later**.

## Final hosted phase attribution

Run `34421130525` is a single diagnostic monotonic trace, not an A/B performance result. Strict side-request accept -> actual Bootstrap entry is **6.118024875 s**. The first target `ClassTransformer` returns only **0.903658 ms** after strict acceptance. From that return to the second target transform begin is **6.100748252 s**, or **99.7176%** of the first-return -> Bootstrap-entry wall. The entry-nearest second `ClassTransformer` return -> actual Bootstrap entry residual is only **6.839641 ms**.

The mutually exclusive request-1-end -> request-2-transform-begin partition is **6.100658054 s**:

- merged/non-overlapping wall inside `MixinProcessor.applyMixins`: **3.967855548 s (65.04%)**;
- wall outside `applyMixins` before Minecraft `Main` submits the worker: **2.131191583 s (34.93%)**;
- submission -> worker entry: **0.771866 ms**;
- worker entry -> second target transform begin: **0.839057 ms**.

Coarse lifecycle boundaries within that same wall are request1 end -> enclosing `modernfix-modernfix.mixins.json` config-prepare exit **71.367856 ms**; enclosing-config exit -> `prepareConfigs` exit **2.413205126 s**; `prepareConfigs` exit -> `select` exit **0.506876 ms**; and `select` exit -> `Main` submission **3.613967273 s**.

The nested callback accounting for the 2.413205126 s `prepareConfigs` suffix is complete in the final run: all recorded config-prepare, `postInitialise`, and plugin-acceptTargets markers are numeric with zero malformed markers. Their callback sums are **1.878878540 s** config prepare, **421.600235 ms** postInitialise, **6.815778 ms** plugin acceptTargets, and **105.910573 ms** remaining parent/interstitial wall. These are nested diagnostics and must not be added to the mutually exclusive `applyMixins` partition.

Largest individual later config-prepare callbacks in this run are Sable NeoForge **182.347 ms**, Lithium **98.443 ms**, Iris **85.296 ms**, SableEdit NeoForge **79.266 ms**, and Sable **66.475 ms**. None alone explains the multi-second gap.

The real second Bootstrap `ClassTransformer` is millisecond-scale: total begin -> return **9.533324 ms**; launch plugins AFTER **8.050453 ms**; writer `ClassNode.accept` **0.705068 ms**; `toByteArray` **0.019086 ms**; writer create **0.000881 ms**; strict BootOptim transformer callback **0.019928 ms**. The immediate post-transform definition/JVM handoff residual to Bootstrap entry is **6.839641 ms**.

This directly rejects the hypothesis that #207's multi-second post-accept wall is predominantly remaining ModLauncher transformer callbacks, ASM writer work, or an immediate SecureJarHandler/JVM tail after the **real** Bootstrap transform.

## Exact-pack validity

The final run reached the real main-menu endpoint. `result.json` reports `startup_total_ms=92246` and `bootoptim_mixin_errors=0`. This startup total contains diagnostic overhead and must not be used as an optimization baseline.

`resource-selection-check.json` is valid: expected resource-pack list exactly equals observed, `reload_count=1`, and `issues=[]`. This fixes the invalidity of the early replacement run `34413781996`, whose resource selection diverged after reaching menu. The final gate is therefore valid for exact-pack diagnostic attribution and replacement identity.

Hosted Xvfb/llvmpipe is still a surrogate and is not physical-laptop/GPU equivalence. This gate does not prove full gameplay compatibility, and paired A/B runs were intentionally not used because no optimization candidate is being promoted.

## Savings ceiling and decision

No TTMM saving is measured. The only directly bounded duplicated Bootstrap transform work is the first side-request `ClassTransformer` itself, which is millisecond-scale (about **2.352 ms total target transform** in the final run; strict accept -> return **0.904 ms**). Reusing those bytes for later classloading is not proven equivalent because request reason, launch-plugin state/audit context, and publication semantics differ; PR #43's global side-load cache remains a no-go.

The mathematical inter-request wall is **6.101 s** in the final run, but it is not a savings estimate. The strongest owner attribution is that **3.968 s / 65.04%** of that wall lies inside the exact Mixin `applyMixins` parent, with another **2.131 s / 34.93%** outside that parent before `Main` submission. Removing or moving either requires a separate semantic-equivalence design and hosted A/B gate.

Decision: keep ModLauncher, SecureJarHandler and FML scheduling stock. Do not implement early `defineClass`, classloader parallelization, transformed-byte reuse, or generic scheduler changes from this evidence. If optimization research continues, the next defensible frontier is exact-version Mixin selection/prepare/apply work, preserving transformed metadata semantics and proving equivalence independently.

## Evidence links

- PR #228: https://github.com/wachipayox/BootOptim/pull/228
- final code head: https://github.com/wachipayox/BootOptim/commit/f10237dfd27dd38b0a8c38d0670488f6e1441e0f
- final green hosted gate: https://github.com/wachipayox/BootOptim/actions/runs/34421130525
- final evidence artifact `10131059318`: https://github.com/wachipayox/BootOptim/actions/runs/34421130525/artifacts/10131059318
- artifact ZIP digest: `sha256:74bf1fbed837f8c5d3b6302d7734aba7920c163a26491bbafc96caeacb735512`
- request-origin instrumentation commit: https://github.com/wachipayox/BootOptim/commit/95ae2305af8985e647e2f3ba7c7926da9ff7202e
- final parser hardening commit: https://github.com/wachipayox/BootOptim/commit/f10237dfd27dd38b0a8c38d0670488f6e1441e0f
- exact ModLauncher upstream: https://github.com/McModLauncher/modlauncher/commit/901c6ea849ae21ee7d464cd97113e77a6101a734

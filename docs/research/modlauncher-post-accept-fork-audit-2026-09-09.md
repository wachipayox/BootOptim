# Agent 94 — ModLauncher post-accept fork audit (2026-09-09)

Status: **NO-GO for `gameContents -> resolveAndBind worker -> stock publication` as an optimization of the already-isolated `transform_accept -> Bootstrap entry` target. GO for an exact-version diagnostic ModLauncher replacement, now validated by a resource-clean hosted exact-pack gate. The original resource-invalid smoke is historical only; the authoritative causal result is `docs/research/modlauncher-bootstrap-double-transform-2026-09-10.md`.**

Authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`. This branch is diagnostic/tooling only; it does not modify integration, production launcher policy, FML scheduling, callback order, classloader ownership, render/GL work, or gameplay.

The requested `docs/research/boot-pipeline-program-2026-09-08.md` is absent at the exact integration SHA. Its contents are not reconstructed.

## Exact pins

| component | exact pin / evidence |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 |
| FancyModLoader | 4.0.43 effective launch contract |
| ModDevGradle | 2.0.144 |
| ModLauncher | `cpw.mods:modlauncher:11.0.5`, upstream `901c6ea849ae21ee7d464cd97113e77a6101a734` |
| `ClassTransformer.java` | blob `a0451dff688b78f075d0e79c3fba540361ba3304` |
| `TransformingClassLoader.java` | blob `89343a57fdc88a4c1cfea7b33e9962d081662257` |
| SecureJarHandler declared by upstream ModLauncher checkout | 3.0.4 |
| SecureJarHandler actually selected by NeoForge 21.1.248 hosted graph | **3.0.8**, stock; not forked |
| Mixin used by the final causal gate | `net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7`, upstream `023e39334850e839c283be413257bf459f40a5d6` |
| hosted runtime | Oracle Java 25.0.4, Ubuntu 22.04, Xvfb/llvmpipe, `ActiveProcessorCount=4` |

Do not conflate the ModLauncher source checkout's declared SJH 3.0.4 with the effective hosted NeoForge graph. The executable resolution gate proves 3.0.8 is the runtime artifact for this pack.

Upstream ModLauncher is LGPL-3.0. No upstream source tree is vendored by this branch; the diagnostic helper clones and exact-pins public upstream source before patching.

## Why the proposed module-resolution worker is on the wrong side of the boundary

PR #207 measured a hosted strict Bootstrap `transform_accept -> Bootstrap entry` wall of **5.130 s**. PR #211 later observed a comparable **6.036645 s** phase with distributed JFR ownership. Those are phase walls, not recoverable-savings estimates.

At exact ModLauncher `901c6ea8`, `Launcher.run` completes GAME-layer construction before launching Minecraft: it materializes `gameContents`, registers GAME jars, gathers transformation services, offers scan resources to launch plugins, validates the target, builds the GAME layer via `JarModuleFinder` / `Configuration.resolveAndBind`, constructs the `TransformingClassLoader`, defines/publishes the module layer and fallbacks, then sets TCCL. Only after this publication does launch dispatch proceed and Minecraft classes begin flowing through `ClassTransformer.transform`.

The #207 strict acceptance point is inside the later target transformation sequence. Therefore moving or overlapping `Configuration.resolveAndBind`, GAME `ModuleClassLoader` construction, `ModuleLayer.defineModules`, fallback publication, or TCCL publication cannot shorten the already-isolated post-accept phase. It could only affect an earlier pre-accept child and would require a separate measurement target.

The concurrency premise is additionally unsafe as a generic production rewrite: transformation-service gathering and `ILaunchPluginService.addResources` are arbitrary callbacks; `SecureJar` objects are live/lazy rather than immutable descriptor records; `ModuleClassLoader` construction binds loader state; and layer/fallback/TCCL publication are observable ordered boundaries. No callback/classloading/scheduler parallelization is justified here.

## Exact diagnostic replacement architecture

The branch's diagnostic runtime stages a fork under the **same exact GAV** `cpw.mods:modlauncher:11.0.5` and exposes it through a Gradle `exclusiveContent` repository filtered only to that coordinate. It does not append a second ModLauncher jar. Before Minecraft starts, a diagnostic resolution task verifies physical jar identity, GAV provenance, built SHA and the effective SecureJarHandler core.

The ModLauncher probe is target-gated to `net.minecraft.server.Bootstrap` and enabled only by `-Dboot_optim.modlauncherForkTrace=true`. It records launch-plugin selection/BEFORE/AFTER, applied transformation-service callbacks, writer construction, `ClassNode.accept`, `toByteArray`, transform return, request origin/reason and classloader/TCCL identity. The strict BootOptim transformer retains the #207 acceptance/entry semantics and fails closed on target drift.

The final lifecycle continuation also uses a same-GAV exact-version Mixin diagnostic fork to mark Mixin selection/prepare/apply boundaries. These probes do not move work, wrap executors, change TCCL, alter GAME/PLUGIN layers, or change rendering/gameplay ownership.

## Historical invalid smoke versus final valid gate

Hosted run `34413781996` was important but limited: it proved the fork was the live named ModLauncher module and reached the main menu, yet its later resource-selection contract failed. Selected/prioritized packs diverged, a second reload differed, fallback was reported, and `options.txt` ended empty. That run is **not** a valid benchmark, A/B result, or equivalence proof and must not be reused as such.

The resource divergence was subsequently isolated as a harness/fixture problem rather than accepted as fork equivalence. Later harness work preserved the exact resource contract and culminated in final run `34421130525` on code head `f10237dfd27dd38b0a8c38d0670488f6e1441e0f`.

Final preflight is green with exactly one Mixin core, one ModLauncher core, and one SecureJarHandler core. The live resolved SHAs are ModLauncher fork `6d65a2827cab304171a286132a75e04d5947a48757d0ef5917c07a349eeb8a1b`, Mixin fork `c248457d43a05891f0598a8c9c4bfb2667632862a385f371ae473cee54711941`, and stock SJH 3.0.8 `945c63d6deafc821616b0380c23867d9b8c2852438f8a6df72732ab933fc587d`.

The same run reached the real main menu, reported no BootOptim Mixin errors, and passed `resource-selection-check.json`: expected == observed, `reload_count=1`, `issues=[]`. Hosted Xvfb/llvmpipe remains a surrogate and this is still diagnostic attribution, not full gameplay/physical-GPU equivalence.

## Superseding causal result

The exact fork proves that the old multi-second `transform_accept -> Bootstrap entry` interval is **not one continuous post-transform tail**. There are two semantically different target requests through the same `TransformingClassLoader`/TCCL:

1. a `main`-thread Mixin launch-plugin transformed-byte metadata request (`securejar_get_maybe_transformed_bytes`, reason `mixin`, caller `MixinLaunchPluginLegacy#getClassNode`), which does not define `Bootstrap`;
2. a later `pool-8-thread-1` actual classloading request (`securejar_reader_to_class`, reason `classloading`, caller `Main#lambda$main$0`), which proceeds toward `defineClass`.

Final run `34421130525` measures strict side-request accept -> actual Bootstrap entry at **6.118024875 s**. The first target `ClassTransformer` returns **0.903658 ms** after strict acceptance; **6.100748252 s** then elapse before the second target transform starts. The entry-nearest second `ClassTransformer` return -> actual Bootstrap entry residual is only **6.839641 ms**.

The causal inter-request wall from request-1 end to request-2 transform begin is **6.100658054 s**. A merged/non-overlapping Mixin parent trace attributes **3.967855548 s (65.04%)** to time inside `MixinProcessor.applyMixins`, **2.131191583 s (34.93%)** to time outside that parent before Minecraft `Main` submission, **0.771866 ms** to submission -> worker entry and **0.839057 ms** to worker entry -> the second target transform.

At the real second transform, ModLauncher itself is millisecond-scale: total `ClassTransformer` **9.533324 ms**, plugins AFTER **8.050453 ms**, writer `ClassNode.accept` **0.705068 ms**, `toByteArray` **0.019086 ms**. This rejects a multi-second writer/SJH-immediate-tail explanation for #207.

Full request stacks, nested Mixin callback accounting, marker-quality checks and exact evidence links are in `docs/research/modlauncher-bootstrap-double-transform-2026-09-10.md`.

## Decision and savings ceiling

No TTMM improvement is claimed. The absolute historical #207 post-accept phase was 5.130 s, but it is not an optimization ceiling after the double-request topology was discovered. The directly duplicated first Bootstrap transform is only millisecond-scale in the final run (~2.352 ms total target transform; 0.904 ms from strict accept to return).

The 6.101 s inter-request wall is a mathematical phase wall, **not** recoverable savings. The strongest current owner attribution is Mixin `applyMixins` at 3.968 s / 65.04%; the remaining 2.131 s outside that parent still requires semantic attribution before any optimization design. Historical laptop 44.361-51.350 s post-accept measurements remain hardware-specific and are not interchangeable with hosted timing.

Keep ModLauncher, SecureJarHandler and FML scheduling stock. Do not revive PR #43-style transformed-byte caching, reuse the metadata-request bytes for classloading, parallelize classloader/layer publication, or perform early `defineClass` from this result. If research continues, the next candidate must target exact-version Mixin selection/prepare/apply work and independently prove semantic equivalence plus hosted A/B benefit.

## Evidence

- PR #228: https://github.com/wachipayox/BootOptim/pull/228
- final code head: https://github.com/wachipayox/BootOptim/commit/f10237dfd27dd38b0a8c38d0670488f6e1441e0f
- final green hosted gate: https://github.com/wachipayox/BootOptim/actions/runs/34421130525
- final artifact `10131059318`: https://github.com/wachipayox/BootOptim/actions/runs/34421130525/artifacts/10131059318
- canonical causal record: `docs/research/modlauncher-bootstrap-double-transform-2026-09-10.md`
- historical resource-invalid replacement smoke: https://github.com/wachipayox/BootOptim/actions/runs/34413781996

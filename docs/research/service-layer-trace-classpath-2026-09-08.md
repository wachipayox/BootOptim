# SERVICE-layer structured trace classpath repair — 2026-09-08

Status: **DIAGNOSTIC INFRASTRUCTURE / FUNCTIONALLY VALIDATED**

This entry repairs the development/exact-pack classpath for the structured boot trace used from the early ModLauncher `SERVICE` layer. It is not a startup optimization. The profile smoke below is functional observability evidence, not performance evidence.

## Base and diagnostic stacking

Integration authority was refreshed as `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

PR #200 (`agent65/structured-boot-trace-20260908` @ `e4132e46eb0bb190e5f2264fac31f45411451682`) is the shared JDK-only `trace-core` foundation and is diagnostic, not integrated production code. Agent 66 PR #201 is intentionally stacked on that foundation and carries forward FML Discovery instrumentation commit `5274a285e585c4bbb4453efc019bddb3ec8447dd`. PR #198's two-writer architecture is not reused: `trace-core/src/main/java` remains the single source of truth for schema, buffer and writer.

## Failed run and actual SERVICE boundary

Original failed hosted exact-pack profile run: https://github.com/wachipayox/BootOptim/actions/runs/34271830496

It compiled `:trace-core` and `:bootstrap`, then failed immediately after `transformation_service_initialize`:

```text
java.lang.NoClassDefFoundError: dev/wachipayox/bootoptim/trace/StructuredBootTrace
  at LAYER SERVICE/main/dev.wachipayox.bootoptim.bootstrap.DiscoveryProfiler.<clinit>(DiscoveryProfiler.java:15)
Caused by: java.lang.ClassNotFoundException: dev.wachipayox.bootoptim.trace.StructuredBootTrace
  at cpw.mods.securejarhandler/cpw.mods.cl.ModuleClassLoader.loadClass(...)
```

The hypothesis was confirmed, with one important refinement. Packaging and ModDev development loading are different boundaries:

- the distributable wrapper can contain a class even when that class is absent from the source-set output loaded as the development SERVICE module;
- root ModDev exposes `project(':bootstrap').sourceSets.main` as the `boot_optim_bootstrap` local-mod output used by the early SERVICE layer;
- compiling `trace-core` as a Gradle dependency does not make that external output readable from `LAYER SERVICE/main`;
- merely adding `trace-core` as another local-mod source set was also insufficient. Startup run https://github.com/wachipayox/BootOptim/actions/runs/34272839010 still reproduced the `NoClassDefFoundError`.

ModDevGradle's `RunUtils` constructs its development mod folders from each source set's `getOutput()` and exports them through `MOD_CLASSES`/`fml.modFolders`. The decisive runtime observation is the SecureJarHandler `SERVICE/main` module boundary above: the trace class must be in the bootstrap output that forms that module.

## Final repair

`bootstrap/build.gradle` now compiles the same shared trace source tree directly into bootstrap's main output:

```gradle
sourceSets.main.java.srcDir(rootProject.file('trace-core/src/main/java'))
```

The regular mod still depends on the standalone JDK-only `:trace-core` project. There is one Java source implementation, not two writers or schemas.

The bootstrap no longer needs a Gradle `implementation project(':trace-core')` dependency or a separate Jar copy step: its normal `sourceSets.main` output already contains `StructuredBootTrace.class`, so the same output is valid for both environments:

- **development/exact-pack:** `boot_optim_bootstrap` exposes only `project(':bootstrap').sourceSets.main`, whose output now contains the shared trace class;
- **distributable:** the normal bootstrap Jar packages that same source-set output.

An intermediate run, https://github.com/wachipayox/BootOptim/actions/runs/34273167016, proved the class was now visible: both FML Discovery probes executed. It then failed later with a Java module `ResolutionException` because the earlier experimental second `trace-core` local source-set mapping was still present. Commit `13b12efcf49e50d6990152db2707c35a7035b566` removed that duplicate path, leaving one SERVICE development unit. The subsequent normal Startup Benchmark reached `main_menu`.

No reflection, `NoClassDefFoundError` catch, silent disable, scheduling change or writer duplication is used.

## FML instrumentation

Commit `5274a285e585c4bbb4453efc019bddb3ec8447dd` emits structured `task_begin/task_end` events around the existing `DiscoveryProfiler` boundaries for:

- `root_mod_discovery`;
- `dependency_discovery`.

The exact-pack run supplies an explicit pack-local trace destination because SERVICE initializes before the regular mod establishes game-directory state.

## Final hosted validation

Validated implementation commit: `13b12efcf49e50d6990152db2707c35a7035b566`.

- Build/package: https://github.com/wachipayox/BootOptim/actions/runs/34273366644 — **success**. The existing package validation step verifies the bootstrap wrapper contains `dev/wachipayox/bootoptim/trace/StructuredBootTrace.class`.
- Normal Startup Benchmark: https://github.com/wachipayox/BootOptim/actions/runs/34273366631 — **success**, `main_menu` reached.
- Exact-pack profile smoke: https://github.com/wachipayox/BootOptim/actions/runs/34273366662 — **success**, `main_menu` reached with `bootoptim_mixin_errors=0`.
- Diagnostic artifact: https://github.com/wachipayox/BootOptim/actions/runs/34273366662/artifacts/10074862086 — `exact-pack-result-smoke-1`, 173,732 bytes, ZIP digest `sha256:0d6c91119d31de8a962835bc8b75024a68bef4f21e2d14886b65e98376d4b4fd`, retention through 2026-09-22.

The artifact was downloaded and inspected. It contains `run-pack-benchmark/logs/bootoptim-trace.jsonl` with exactly one header and one summary:

```text
schema=bootoptim.boottrace
schema_version=1
mode=profile
measurement_origin=hosted_exact_pack
endpoint=main_menu
clock_kind=monotonic
clock_source=System.nanoTime
dropped_events=0
```

Event integrity:

| Phase | begin | end | task id | inclusive monotonic interval |
| --- | ---: | ---: | ---: | ---: |
| `root_mod_discovery` | 1 | 1 | 1 | 557.368966 ms |
| `dependency_discovery` | 1 | 1 | 2 | 7515.262445 ms |

The summary reports `buffered_events=4`, `task_begin=2`, `task_end=2`, `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`, and `error=0`. A BootOptim-scoped scan of the uploaded console/latest log found no BootOptim `ERROR`/`FATAL`, no BootOptim class-not-found failure, and no module `ResolutionException`. The pack has unrelated optional-target Mixin class-loading warnings from other mods; they are not BootOptim trace failures.

The functional smoke reported `main_menu_ms=88782` / `startup_total_ms=88782`, `mod_entrypoint_ms=30020`, `reload_to_fancymenu_finish_ms=41315`, and `fancymenu_panorama_ms=4602.114`. These are **hosted exact-pack profile endpoint/inclusive telemetry**, not a paired performance result. Likewise the two Discovery intervals above are inclusive task wall intervals from `System.nanoTime`; they must not be summed and presented as critical-path savings. No startup improvement is claimed.

Environment: hosted Ubuntu, 4 active processors, Oracle JDK 25.0.4 runtime, Xvfb + Mesa llvmpipe. This is sufficient for classloader/trace functional validation, but it is not physical GPU/storage performance evidence. For this diagnostic repair the physical status is **sin evidencia física**, and no laptop run is required to establish the classpath fix.

## Risks and contract

- **Classloader/package risk:** directly covered by both the ModDev exact-pack SERVICE path and the packaged-bootstrap validation.
- **Duplicate identity risk:** the failed intermediate module-resolution run demonstrates why `trace-core` must not also be exposed as a second development source-set/module. Final dev mapping contains bootstrap only.
- **Semantic/gameplay risk:** none intended; no discovery ordering, callbacks, resource reload, scheduling or gameplay behavior changed.
- **Render/OpenGL risk:** none introduced; no render-thread or OpenGL work moved. llvmpipe is only a functional hosted endpoint, not visual-performance evidence.
- **Diagnostic stacking risk:** #201 depends on the #200 diagnostic foundation. If #200 lands first, rebase #201 so only the SERVICE classpath repair plus intended FML instrumentation remain; do not mistake the stacked diagnostic diff for already-integrated production code.

## Decision

The SERVICE classpath hypothesis is **confirmed and repaired**. Keep #201 diagnostic. The next concrete integration decision is to land/rebase it in dependency order with #200, then use the repaired single-schema trace for subsequent FML Discovery research. Do not treat this smoke as a performance win and do not request physical A/B for this classpath repair.

# SERVICE-layer structured trace classpath repair — 2026-09-08

Status: **DIAGNOSTIC INFRASTRUCTURE / FUNCTIONAL VALIDATION PENDING**

This entry repairs the development/exact-pack classpath for the structured boot trace used from the early ModLauncher `SERVICE` layer. It is not a startup optimization and no timing from the profile smoke is performance evidence.

## Base and diagnostic stacking

The integration authority was refreshed as `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

PR #200 (`agent65/structured-boot-trace-20260908` @ `e4132e46eb0bb190e5f2264fac31f45411451682`) is the shared JDK-only `trace-core` foundation and is still an open diagnostic PR, not integrated production code. This Agent 66 branch is intentionally stacked on that diagnostic foundation, whose merge base is the integration SHA above, then fast-forwarded by the single public FML instrumentation commit `5274a285e585c4bbb4453efc019bddb3ec8447dd` from `codex/trace-core-profile`. The resulting PR must therefore be treated as a diagnostic continuation of #200, not as proof that trace-core exists on integration.

PR #198 was reviewed and is not reused: it had separate early/regular writers. The contract here remains one schema/API implementation in `trace-core`.

## Failure and actual development boundary

Failed hosted exact-pack profile run: https://github.com/wachipayox/BootOptim/actions/runs/34271830496

The run compiled `:trace-core:compileJava`, `:trace-core:classes`, `:trace-core:jar`, `:bootstrap:compileJava` and `:bootstrap:classes`, then failed immediately after `transformation_service_initialize`:

```text
java.lang.NoClassDefFoundError: dev/wachipayox/bootoptim/trace/StructuredBootTrace
  at LAYER SERVICE/main/dev.wachipayox.bootoptim.bootstrap.DiscoveryProfiler.<clinit>(DiscoveryProfiler.java:15)
Caused by: java.lang.ClassNotFoundException: dev.wachipayox.bootoptim.trace.StructuredBootTrace
  at cpw.mods.securejarhandler/cpw.mods.cl.ModuleClassLoader.loadClass(...)
```

The important distinction is packaging versus ModDev development loading:

- `bootstrap/build.gradle` has `implementation project(':trace-core')`, so bootstrap compiles against trace-core.
- the bootstrap distributable `jar` explicitly embeds `project(':trace-core').sourceSets.main.output`, and PR #200's Build CI asserts that `StructuredBootTrace.class` is physically present in the packaged wrapper;
- development/exact-pack does not launch that wrapper JAR. Root `build.gradle` declares a local ModDev mod `boot_optim_bootstrap` whose `MOD_CLASSES`/fake-mod unit contained only `project(':bootstrap').sourceSets.main`;
- therefore the SERVICE module could resolve `DiscoveryProfiler` from bootstrap output while `trace-core` remained merely a Gradle dependency/runtime artifact outside that local SERVICE unit. Compilation success and even `:trace-core:jar` creation do not make the class visible to that `ModuleClassLoader`.

This matches ModDevGradle's documented local-mod model: one local mod may contain multiple `sourceSet` entries, which are grouped into the same development fake mod/JAR. The failure is therefore a dev source-set mapping defect, not a missing compilation dependency and not a packaged-JAR defect.

## Repair

The smallest repair is to make development mirror packaging:

```gradle
'boot_optim_bootstrap' {
    sourceSet(project(':bootstrap').sourceSets.main)
    sourceSet(project(':trace-core').sourceSets.main)
}
```

No source is copied and no second writer/schema is introduced. `trace-core` remains the sole implementation. `:bootstrap:classes` already depends transitively on trace-core compilation through `implementation project(':trace-core')`, as the failed run itself showed.

Contract after this change:

- **packaged runtime:** trace-core classes are embedded in the bootstrap wrapper JAR;
- **ModDev/exact-pack runtime:** bootstrap and trace-core outputs are grouped into the same `boot_optim_bootstrap` local-mod unit exposed to the early SERVICE layer.

The regular mod keeps its compile dependency on the same `trace-core` API; there is no reflective fallback and no `NoClassDefFoundError` catch.

## FML trace instrumentation carried forward

Commit `5274a285e585c4bbb4453efc019bddb3ec8447dd` adds structured `task_begin/task_end` events to the existing `DiscoveryProfiler` boundaries for:

- `root_mod_discovery`;
- `dependency_discovery`.

The exact-pack runner supplies an explicit pack-local trace path because the SERVICE layer initializes before the regular mod can establish game-directory state. The workflow collects `run-pack-benchmark/logs/bootoptim-trace.jsonl` after JVM exit.

## Validation contract

Required functional smoke JVM properties:

```text
-Dboot_optim.bootTrace.mode=profile
-Dboot_optim.bootTrace.origin=hosted_exact_pack
-Dboot_optim.bootTrace.endpoint=main_menu
```

Acceptance is functional observability only:

1. compile/package green and packaged bootstrap still contains `StructuredBootTrace.class`;
2. hosted exact-pack reaches `main_menu` without BootOptim/Mixin errors;
3. diagnostic artifact contains `run-pack-benchmark/logs/bootoptim-trace.jsonl`;
4. one coherent `trace_header` for schema `bootoptim.boottrace` v1, with origin `hosted_exact_pack` and endpoint `main_menu`;
5. matched FML discovery `task_begin/task_end` pairs, no declared event loss and no trace errors.

Any elapsed values in this smoke are profile-mode inclusive/functional telemetry and are not a benchmark or claimed TTMM improvement.

## Risks

- **Classloader/package risk:** the dev grouping must match the packaged wrapper's ownership. A green exact-pack profile smoke is the direct proof for the ModDev SERVICE path; packaged-JAR CI separately covers distribution.
- **Duplicate identity risk:** trace-core must stay in the bootstrap local-mod unit rather than be declared as a second independent dev mod/module. The change groups source sets under the existing `boot_optim_bootstrap` id.
- **Semantic/gameplay risk:** none intended. This changes diagnostic class visibility only; it does not alter discovery ordering, scheduling, callbacks, resource reload, render-thread/OpenGL work or gameplay.
- **Visual risk:** none introduced by the classpath repair. Hosted llvmpipe reaches the same smoke endpoint but is not visual-performance evidence.

## Evidence status

Repaired hosted run and artifact inspection: **pending at first commit; update this section with the PR run before concluding the task.**

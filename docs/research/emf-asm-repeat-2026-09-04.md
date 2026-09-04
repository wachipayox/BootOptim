# EMF ASM compile repetition diagnostic — 2026-09-04

Status: **ACTIVE / DIAGNOSTIC ONLY**

Base: `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

Target pack version: Entity Model Features **3.2.4**. Exact upstream commit identified from EMF history: `9cda2159e9fd4ef631b4dda0500fe1cfc46520f1` (`Traben-0/Entity_Model_Features`, commit message `3.2.4`).

## Why this front

PR #92 attributed the expensive entity/block-entity renderer reload tail. `EntityModelSet.bakeLayer` was only tens of milliseconds on hosted exact pack, while provider construction dominated and scoped samples were hot in EMF/Fresh Animations animation parsing/optimization and ASM compilation.

PRs #93–#95 then established a larger lifecycle optimization: the initial renderer rebuild is not needed to reach title, can be deferred, and the original stock/NeoForge reload bodies can execute successfully after title. That lane remains blocked only on a physical world-entry/visual gate.

If the deferred payment is still too large on the 4-thread Windows laptop, the next useful question is not another later scheduling boundary. It is whether EMF repeatedly recompiles equivalent animation programs while constructing renderer providers.

## Exact EMF 3.2.4 source findings

`EMFManager.setupAnimationsFromJemToModel(...)` does, for every model instance:

1. creates fresh `AnimLineData` containing animation key/expression plus the concrete `EMFModelPart` / applier for that model;
2. parses and optimizes each expression with `MathExpressionParser.getOptimizedExpression(..., AnimSetupContext)`;
3. when ASM maths is enabled, creates a new `ASMVariableHandler`;
4. calls `ASMParser.compileOrNull(...)`;
5. constructs a fresh `ASMAnimationHandler`;
6. binds variable suppliers and output consumers to the concrete model/context;
7. calls `finishAndValidate()` and installs the handler on that model root.

The generated ASM executor itself has the narrow interface:

```text
execute(float[] floats, boolean[] bools)
```

`ASMAnimationHandler` separately builds context/model-specific suppliers and consumers. The compiled executor therefore does **not** directly capture `ModelPart` instances.

However, caching by raw expression/JEM text before stock parsing is not safe. `VariableRegistry.getVariable(...)` resolves context-dependent variables during parsing. If a variable cannot be created for a particular model context, EMF replaces it with a constant zero/false component. Consequently the same expression text can legitimately compile to a different program for a different part map.

This rejects a generic `expression -> parsed tree/executor` cache.

## Narrow remaining hypothesis

Keep all stock context-sensitive work through parse/validation and ask only:

> How many calls to `ASMParser.compileOrNull` inside renderer reconstruction have already produced the same source program **and** the same resolved ASM variable/output layout, and how much stock `compileOrNull` wall belongs to the second and later calls in those groups?

Even equality of this diagnostic signature is deliberately **not** treated as proof that two executors are interchangeable. A production candidate would still require canonical generated-bytecode equality (or an upstream/source-level equivalent proof) before executor reuse.

The diagnostic exists only to decide whether that stronger implementation work has a meaningful ceiling.

## Instrumentation

Property:

```text
-Dboot_optim.profileEmfAsmCompileRepeat=true
```

Scopes are the authoritative renderer reload callbacks:

- `BlockEntityRenderDispatcher.onResourceManagerReload`;
- `EntityRenderDispatcher.onResourceManagerReload`.

The optional EMF hook targets:

```text
traben.entity_model_features.models.animation.math.asm.ASMParser.compileOrNull
```

via `@Pseudo`/string target and `@Coerce Object` parameters. BootOptim therefore gains no compile or runtime dependency on EMF. With the property disabled, the hooks only execute cheap disabled guards; no EMF behavior changes.

For each stock `compileOrNull` call the diagnostic records its body wall. The timer starts only after the profiler has built its input signature and stops before output reflection, so `compile_ms` excludes the profiler's reflection/hash work.

Two grouping levels are counted:

1. **source signature** — ordered animation key + expression + boolean output type;
2. **template-candidate signature** — source signature plus the stock `ASMVariableHandler` float/bool variable order, read/write roles, and stock `asmIndex` assigned to every output line.

One bounded aggregate marker is emitted per dispatcher:

```text
BOOTOPTIM_EMF_ASM_REPEAT
```

Fields:

- `calls` / `compile_ms`;
- `failures` / `reflection_failures`;
- `source_unique`, `source_repeat_calls`, `source_repeat_ms`;
- `template_candidate_unique`, `template_candidate_repeat_calls`, `template_candidate_repeat_ms`;
- up to five largest repeated template-candidate groups by repeated compile wall.

There is no per-expression logging and no cache/reuse/reordering.

## Interpretation discipline

`template_candidate_repeat_ms` is a **ceiling**, not a savings claim. It measures stock `compileOrNull` wall on repeated resolved-layout signatures. A real cache would still have lookup/hash overhead and must prove generated-program identity.

The diagnostic intentionally does **not** claim that parse/optimization repetition is reusable. Parse is context-sensitive in EMF 3.2.4 and remains stock under this premise.

A worthwhile follow-up requires:

- material total ASM compile wall inside entity renderer reconstruction;
- a high repeated-template fraction by wall, not merely by call count;
- zero EMF ASM compile failures introduced by the diagnostic;
- no BootOptim Mixin failures;
- evidence large enough to justify version-specific compatibility work.

If repeated-template wall is small, this lane stops and the project keeps the renderer lifecycle optimization as the primary mechanism.

## Possible implementation only if the ceiling is material

The clean source-level architecture would be to separate EMF's ASM operation into:

1. stock parse/context validation;
2. deterministic code generation into a canonical program representation/bytecode;
3. cache lookup by exact canonical generated program;
4. class definition / method-handle creation only on cache miss;
5. always create a fresh `ASMAnimationHandler`, variable suppliers, model-part consumers and per-entity result state.

EMF currently gives each generated class a unique name and a fresh class loader. Reusing a canonical executor could avoid repeated class definition/verification/MethodHandle setup without sharing model state. BootOptim should not ship this by copying/overwriting a large third-party method unless a small robust interception point is proven. An upstream patch or controlled EMF fork would be cleaner if the user ever controls that dependency; current repository history does not establish EMF as user-owned/edited.

## Gates

1. Build and standard Startup CI with property off.
2. Hosted exact-pack smoke with property on; must emit both dispatcher aggregate markers and reach title with zero BootOptim Mixin failures.
3. Inspect aggregate repetition wall. Do not implement a cache if the ceiling is weak.
4. If strong, open a separate candidate branch with canonical-bytecode identity proof and same-branch exact-pack A/B.
5. Renderer/world visual semantics remain independently governed by PR #95's physical gate.

## Related

- PR #92 — renderer layer/provider attribution and EMF scoped hotspot evidence.
- PR #93 — renderer defer ceiling.
- PR #94 — real first-consumer defer, hosted 3×3 win.
- PR #95 — coordinated world warmup and successful post-title stock callback forcing.
- EMF 3.2.4 upstream SHA `9cda2159e9fd4ef631b4dda0500fe1cfc46520f1`.

# ModelManager dispatch identifier reuse: bounded audit

Date: 2026-09-30. Status: **NO-GO for generic identifier/string reuse;
restricted-domain investigation remains unproven**.

Integration authority refreshed before inspection:
`agent/integration-current` @ `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.
No runtime code, instance files, laptop access, game launch or new profiler was
introduced by this audit.

## Scope and prior evidence

The requested small candidate was to avoid rebuilding block-state model
identifiers during `ModelManager.loadModels` dispatch, using identifiers already
created by `BlockStateModelLoader` during the same `ModelBakery` construction.
It explicitly excludes baked-model reuse, callback removal, multipart grouping,
and Palladium internals (separate concurrent investigations).

Relevant historical bodies/comments reviewed: #36, #214 and #217. #36's rejected
top-level baked-model identity cache is a different operation and is not being
reopened. #214/#217 demonstrate that enclosing `loadModels` includes bake work;
their post-bake residuals were respectively 1,144.994 ms and 816.329 ms in hosted
diagnostic runs. They are not timings of identifier formatting alone. The parent
investigation's fast-PC manual-reload trace has a roughly 0.94-second residual;
it likewise contains model callbacks. These are different measurement origins,
not an A/B population. No savings claim follows from them or from state counts.

Current integration contains neither a dispatch identifier cache nor a property
string cache. Existing active #296/#298 candidates target Decocraft geometry and
FerriteCore table capacity, not this operation; #295's rejected multipart direct
dependency collector also targets a different operation.

## Confirmed source path

Local exact decompiled Minecraft 1.21.1 model source in
`C:/BootOptimBench/analysis-reload-20260930/` was inspected:

1. `BlockStateModelLoader.loadBlockStateDefinitions` enumerates possible states,
   creates `BlockModelShaper.stateToModelLocation(location, state)`, and places
   those keys in its definition-local map before parsing the definitions.
2. `ModelBakery` invokes `loadAllBlockStates`, then item/special registration and
   parent resolution. There is no public state-to-identifier table carried from
   the loader into the bakery/manager.
3. `ModelManager.loadModels` invokes the complete `bakeModels` path, then obtains
   the current baked top-level map. For each registry block state, it creates a
   fresh identifier, performs `map.getOrDefault(identifier, missingModel)`, and
   inserts that result in a fresh `IdentityHashMap<BlockState, BakedModel>`.
4. `BlockModelShaper.stateToModelLocation` creates a fresh `ModelResourceLocation`
   from `statePropertiesToString(state.getValues())`. The latter enumerates the
   map in its existing order and calls the virtual `Property.getName(value)` for
   every value. It does not perform sorting or resource reads.

Therefore repeated formatting is real, but the claimed removable time is not
yet measured independently from dispatch callbacks and other work.

## Compatibility boundaries

Reusing the original `ModelResourceLocation` object is not justified by its
vanilla record shape. Exact installed Palladium 1.1.8 bytecode exposes its backing
`String[]` via public `ModelResourceLocationProperties.palladium$properties()`.
When its applicable configuration is enabled, `getVariant` joins that array and
`hashCode` incorporates its content. Reuse would alias the earlier identifier;
stock dispatch constructs a new object with a new array. This audit does **not**
claim any installed consumer actually mutates that array. The public mutation
surface is enough to reject a generic immutable-key assumption.

Reusing only a `String` avoids that alias and preserves fresh constructor hooks,
but state/value-map immutability alone does not establish formatter purity.
`Property.getName(value)` is an extension boundary: custom property classes and
custom enum serialization can consult mutable configuration or perform
observable work. A generic cache would skip the second callback or retain a
value made before bake-result callbacks ran. Comparing/rebuilding the string on
every hit removes the intended savings.

Even a restricted formatter cache needs an ownership path from the loader into
the bakery and dispatch, plus explicit abort/reload cleanup. A global state cache
or a worker-thread local that survives an exceptional constructor is unsuitable.
All baked-model modification callbacks and current-generation map replacements
must still run; cached baked results cannot substitute for the dispatch lookup.

## Decision and reopening gate

Do not implement the generic identifier-object reuse or generic serialized-value
reuse based only on repeated counts and the enclosing 0.8–1.1-second residual.
No build/hosted runtime campaign is warranted for a code change that has not met
its semantic premise. This is an audit closure, not evidence that formatting is
free or that all dispatch optimization is impossible.

Reopen a deliberately restricted version only when all of these are available:

- independently attributed formatter/identifier cost or allocation pressure in
  the existing trace, separated from callbacks and nested bake work;
- an explicit proven pure property domain (exact stock primitive properties,
  plus separately verified enum serializers; unknown classes fail open);
- a generation-owned cache with abort cleanup, preserving fresh identifier
  construction, original entry order, current baked-map lookup and all callbacks;
- an equivalence checker covering callback-sensitive custom properties, mutable
  Palladium arrays, missing/replaced baked keys and successive reload generations;
- hosted exact-pack runtime and paired timing evidence before physical testing.

Another simpler alternative is allocation reduction in the stock formatter
without caching or skipping callbacks, but its benefit has not been measured
and it should not be presented as a seconds-scale optimization.

## Existing fast-PC caller samples: restricted-domain decision

Re-reading the existing multifocus recording (no new recording/run), first
manual F3+T origin `2026-09-28T21:37:10.604+02:00`, gives exact markers
`bakeEnd=14941`, `loadModelsEnd=15768` ms: **827 ms** outside the enclosing bake
in this generation. The other two logged residuals were **871 / 762 ms**. This
corrects applying the older roughly 0.94-second residual to this particular run.

In the first generation's `[14.941, 15.768]` second window, restricted to the
actual `Worker-ResourceReload-0` owner, existing JFR execution samples contain:

- 75 owner-thread samples total;
- 22 with dispatch `lambda$loadModels$18` on their stack;
- 17 with `stateToModelLocation` on their stack (includes constructor work);
- only 7 with `statePropertiesToString` (4 `StringBuilder.append` leaves,
  3 `AbstractStringBuilder.ensureCapacityNewCoder` leaves);
- 9 with `onModifyBakingResult`, including DragonLib callbacks.

These are stack-presence counts, **not exclusive milliseconds or a confidence
interval**. Samples do not identify which property domain each call serializes.
The restricted stock-property cache would attack only a subset of the seven
formatter samples and leave fresh MRL construction/Palladium work intact. A
strict exact Boolean/Integer domain could preserve the known stock serializer,
but an enum's `StringRepresentable` implementation also needs an independent
purity proof; checking only exact `EnumProperty` class is insufficient.

Decision: a multi-hook generation cache retaining hundreds of thousands of
state/property maps is not justified by this bounded evidence. Do not launch a
new profiler or CI campaign solely for it. The larger 17-sample fresh-identifier
path overlaps Palladium's separate optimization lane; optimize that lane first
and reassess whether plain string-builder allocation remains material.

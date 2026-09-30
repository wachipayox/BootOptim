# Optional Sodium coordinate bridge audit

Date: 2026-09-30. Status: **SOURCE/BINARY AND JVM LINKAGE VALIDATED;
full transformed-client runtime remains a separate gate**.

This supports the concurrent `codex/sodium-quad-classification-20260930`
candidate. No root candidate files, instance files, game/laptop run, or build
session were changed by this audit. Integration base was refreshed to
`a0b8fdc05dd97267698ebbce1f561ac4895d3b74` earlier in the bounded parent assignment.

## Actual candidate and pinned targets

The inspected candidate defines an empty `@Pseudo @Mixin(remap=false)` interface
targeting
`net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView` and extending the
BootOptim-owned `QuadCoordinateView`. The latter declares only `float getX(int)`,
`getY(int)` and `getZ(int)`, matching all three exact Sodium method descriptors.
There are no Sodium classes in the bridge method signatures/imports.

The static HEAD callback targets
`net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFlags` (the
`properties` package segment is required), whose exact pinned signature is
`getQuadFlags(ModelQuadView, net.minecraft.core.Direction): int`. Its handler
receives `@Coerce Object`, stock `Direction`, and `CallbackInfoReturnable<Integer>`.
It falls through unless explicitly enabled, exact BakedQuad class, own bridge,
non-null face and exact supported Sodium version are available.

## Mixin implementation proof

Locally cached Mixin source `sponge-mixin 0.15.2+mixin.0.8.7` and actual
`org.spongepowered:mixin:0.8.7` binary were inspected, not a guessed API:

- `MixinInfo.State.readImplementations` includes the mixin class's declared
  superinterfaces.
- `MixinInfo.getVariant` classifies a completely empty interface as ACCESSOR.
  This does not discard its declared parent interface; the accessor subtype
  also supports interface targets on the configured Java compatibility level.
- `MixinApplicatorInterface.applyInterfaces` explicitly appends each collected
  interface to the target interface's interface list and updates ClassInfo.
  Actual 0.8.7 `javap` output confirmed the same operation.
- `CallbackInjector.Callback.checkDescriptor` accepts annotated reference
  parameter coercion through `Injector.canCoerce`; Object handler types accept
  the target interface's reference type via the hierarchy check. The actual
  0.8.7 binary confirmed this `ClassInfo.hasSuperClass(..., ALL, true)` route.

The seam therefore does not require an optional Sodium compile dependency or a
reflective coordinate read on each quad. `@Pseudo` string targets permit absence
of the optional target classes; full absence/startup behavior still belongs in
runtime validation. Do not introduce Sodium class literals in the helper or
bridge, which would undo that property.

## Getter observable order

Pinned Sodium bytecode reads vertex 0 X/Y/Z, then vertex 1 X/Y/Z, vertex 2 X/Y/Z,
vertex 3 X/Y/Z, before inspecting the face. The candidate helper uses the same
twelve reads in the same order. A null face must fall through the entire stock
method so getter behavior before the stock null failure is preserved. An explicit
null-quad fallback is also preferable to failing in `quad.getClass()` inside the
handler; that suggestion was sent to the root agent.

## Independent linkage fixture and limits

A standalone Java/ASM fixture at
`C:/BootOptimBench/analysis-reload-20260930/seam-harness/BridgeSeamHarness.java`
compiled with `--release 21 -proc:none` and **only ASM + Mixin jars**. It reads
the exact installed Sodium interface bytecode, adds the own superinterface and
empty accessor mixin interface as the audited Mixin applicator does, synthesizes
an implementing fixture class, then checks:

- `instanceof` / cast to the own interface succeeds;
- all twelve calls dispatch in `X0Y0Z0...X3Y3Z3` order;
- a compile-only `@Coerce Object` static callback body cancels eligible results;
- an unknown Object passes through without cancellation.

Result: **PASS**. The fixture substitutes a plain Integer face argument for
Minecraft Direction to keep compilation independent of both Minecraft and
Sodium; it does not test the production descriptor matcher itself. It is JVM
linkage/compile evidence, not an actual Mixin transformer invocation. No full
runtime success, performance benefit, or atlas/visual equivalence is claimed.
The production build and hosted exact-pack run must still prove the actual
transformed interface and callback apply together on the pinned mod version.

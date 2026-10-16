# Per-state shape memo for Create Deco catwalk railings and CreateFood pizzas — 2026-10-16

Follows [block-shape-or-memo](block-shape-or-memo-2026-10-16.md) (#369), whose "Lazy `getShape`"
row (Belt, Cable, Catwalk, Chute, Furnish; render thread, MoreCulling `initShapeCache`) it left
open. Not a repeat of the archived
[`moreculling-initshapecache-algorithmic-2026-09-08`](../archive/research/moreculling-initshapecache-algorithmic-2026-09-08.md)
rejections: no face cache, no identity table over `VoxelShape`, no reuse of the vanilla
`BlockStateBase.Cache`. This memoizes the *mod's own* `getShape` result per block state, only
for methods whose source is a pure function of the state.

## Premise (fast PC, JFR)

Origin: fast PC, Wachiland Launcher, Beta rev 3, RRLS diagnostic (in-world manual reload),
BootOptim integration `c5edcd6`. In the final in-world F3+T freeze (~3.3 s of render-thread
samples) MoreCulling owns 54 %; inside `initShapeCache`, 26 of 34 samples are
`com.github.talrey.createdeco.blocks.CatwalkRailingBlock.getShape` and 8
`dev.averageanime.block.type.pie.PizzaBlock.getShape`.

Source (Create Deco 2.1.3, CreateFood 2.5.0, decompiled):

- `CatwalkRailingBlock.getShape` → `getInteractionShape(state, …)`: starts from `Shapes.empty()`
  and `Shapes.join(…, OR)`s up to four static boxes chosen by four boolean properties. Level and
  position are unused.
- `PizzaBlock.getShape`: `SHAPE_BY_BITE[BITES]`, rotated by `FACING` on every call.

Both rebuild an identical shape on every call; MoreCulling calls them for every state on each
resource reload, and the game calls them again for outlines/collisions.

## Mechanism

`StateShapeMemo` (default on; `-Dboot_optim.stateShapeMemo=false` restores stock): `@Pseudo`
HEAD/RETURN injections on each class's `getShape`; key = interned `BlockState`, value = the
shape stock returned. Differential guard: the first 256 hits per mechanism and 1 in 1,024
afterwards re-run stock and compare exactly (`BlockShapeOrMemo.sameShape`: class, grids, boxes);
a mismatch disables that mechanism with `[WBOMBO-COMPAT] FALLBACK`. Not version-pinned; a missing
class or method is reported by `CompatInjectionCheck`.

## Results

Pending: hosted paired A/B (segment: MoreCulling shape listener in the startup reload) and the
fast-PC in-world F3+T A/B (needs the user's go-ahead: the PC is shared).

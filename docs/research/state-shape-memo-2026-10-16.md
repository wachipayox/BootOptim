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
shape stock returned. Differential guard: the first 32 repeat hits per mechanism and 1 in 256
afterwards re-run stock and compare exactly (`BlockShapeOrMemo.sameShape`: class, grids, boxes);
a mismatch disables that mechanism with `[WBOMBO-COMPAT] FALLBACK`. Not version-pinned; a missing
class or method is reported by `CompatInjectionCheck`.

## Results

Fast PC (i5-12600KF), Wachiland Launcher, Beta rev 3 (`--no-update`), Java 25, RRLS test JAR
(world-open overlap + diagnostics). Enter "New World" 0.5 s after model publication, in-world
F3+T 20 s later. Order primer, ctl1, cand1, cand2, ctl2; control `-Dboot_optim.stateShapeMemo=false`.
"Final pause" = longest frame gap during the in-world F3+T.

| Guard | Final pause ctl (ms) | Final pause cand (ms) | F3+T ctl / cand (s) | ACTIVE |
|---|---|---|---|---|
| warm-up 256, 1/1,024 (`f602318`) | 2,473 / 2,510 | 2,225 / 2,276 | 12.96, 12.66 / 11.63, 11.69 | pizza only, ~1 s before the reload ends; railings never left warm-up |
| warm-up 32, 1/256 (`ae59293`) | 2,323 / 2,469 | 2,155 / 2,204 | 12.18, 12.13 / 12.61, 12.16 | both, at the start of MoreCulling's shape pass; 0 FALLBACK |

- Final F3+T pause: about -0.2 s, with non-overlapping ranges in both series. This matches the JFR
  ceiling (34 of 165 render-thread samples in the final pause, about 0.34 s).
- Total F3+T time does not change measurably. Its run-to-run spread is about 2 s (11.5-13.5 s in
  the RRLS#7 series with this memo off), so the -1.1 s seen with the first guard is noise.
- With warm-up 256 the guard defeated the memo: each state is asked about once per reload, so a
  single F3+T never got past verification.
- Hosted CI (to menu): 0 mixin errors and no FALLBACK. It cannot reach ACTIVE, because there are
  too few repeat lookups before the menu.

Decision: integrate (default on, guarded).

# Decocraft rotation temporaries

Status: **INTEGRATED**, default ON on PC (decision relayed by the coordinator session, 2026-10-16; kill switch kept). Migrated from Android Support `824b37d` (`memory.decocraftRotationTemporaries`). Ledger: `docs/research/decocraft-support-migration-2026-10-16.md`. Uses the code-shape contracts and ownership rule of [decocraft-compact-structures](decocraft-compact-structures.md).

## Mechanism

Two allocation sites inside `BlockbenchBakery` rotation helpers, replaced with `@WrapOperation` on the `new` expression (`require = 0`):

- `applyModelRotation(Vector3, Vector3, Matrix4f)`: `new Matrix4f()` used only as the receiver of `equals(matrix)` (identity test). A shared, never-mutated identity gives the same answer. It is used only when the argument's class is exactly `org.joml.Matrix4f`; `null` and subclasses (a subclass could override `equals` and retain or mutate its argument) get the original fresh matrix.
- `applyElementRotation(Vector3, Element)`: `new Vector3(x, y, z)` snapshot of the element origin, only read afterwards. One owned `Vector3` per bakery instance, overwritten per call, never an alias of a model's mutable origin. Calls from another thread, or a `Vector3` that is not the exact public mutable `float x/y/z` shape, take the original constructor (an unexpected shape also disables the mechanism with `FALLBACK origin_vector_shape_changed`). Reads and writes keep the original order and raw float bits.

## Guard

22 method fingerprints (`bootoptim-decocraft-contracts.properties`): both rotation helpers, `Vector3.<init>(FFF)` and `Vector3.set(FFF)` (used by the constructor), `Matrix4f.<init>()`, `Matrix4f.equals` and the sixteen `m00()`–`m33()` getters of JOML. Class bytes are read without initialising anything (JOML from its library resource). A changed body, missing class, target changed before apply, missed injector or legacy Support on Android → stock + `[WBOMBO-COMPAT] FALLBACK`; `ACTIVE` at first use. A different JOML build with a different `equals`/getter body therefore also falls back.

Kill switch: `-Dboot_optim.decocraftRotationTemporaries=false`.

## Evidence and limits

- Mobile (Vivo/Java 21), warmed synthetic owned inputs: identity matrix 80 → 0 B/call, origin copy 24 → 0 B/call, after subtracting 40 B of fixed probe overhead. **Allocation only**: not retained memory, not CPU time, not FPS, and not the pack's whole allocation rate.
- Host contract (`./gradlew check` → `decocraftCompactStructuresContract`): 100,000 raw-bit snapshots (NaN/infinity/signed zero), source origin untouched, cross-thread and unknown-shape fallback, per-bakery separation, JOML identity equivalence over 10,000 matrices including NaN and `-0f`, subclass excluded.
- PC: the hosted smoke checks menu, `ACTIVE` and no `FALLBACK`; it does not measure this allocation. Reopen for a PC allocation figure only with a representative attribution (JFR allocation by site on the pack).

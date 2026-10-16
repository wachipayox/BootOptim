# Decocraft optimizations migrated from Android Support — 2026-10-16

Ledger for moving the accepted Decocraft memory/allocation mechanisms of WachilandAndroidSupport (frozen snapshot `824b37d`, candidate `0.2.57-bake-staging`) into BootOptim, one bounded PR per mechanism or closely related group. BootOptim becomes the owner; Support is being stripped of the same code. Not copied: Android-only work, the rejected facing-vector scratch (archived in Support `archive/codex/decocraft-facing-scratch-rejected-20261016`, commit `60c6fa4`: the warmed control fell to 40 B per window, the JIT already removes the premise).

## Ground rules for every row

- Mobile evidence (one Vivo / Mali / MobileGlues / Java 21) does **not** prove a PC saving. Retained memory, temporary allocation and time are separate columns; each number states its origin.
- Guards are code-shape contracts (`DecocraftContracts`), never a Decocraft version or JAR hash. Fallback = stock + `[WBOMBO-COMPAT] FALLBACK`.
- Defaults on PC need the user's decision. The 3D inventory icons change appearance and stay optional (default off).
- Source registry of the mobile results: Support `docs/compatibility/2026-10-14-reload-memory.md` at `824b37d` (final conclusions supersede older numbers; Support48/49 were invalid measurements caused by a fading overlay, not a rejection of compression).

## Status

| Mechanism | Order | Status | PR | Production catalog |
|---|---|---|---|---|
| Private `Direction.values()` array | 1 | in PR | see catalog | [decocraft-compact-structures](../optimizations/compatibility/decocraft-compact-structures.md) |
| Ordered compact face maps | 1 | in PR | see catalog | same entry |
| Equal embedded `data:image` Strings | 2 | not started | | |
| Rotation temporaries (identity `Matrix4f`, origin snapshot) | 3 | in PR (stacked on the structures PR) | see PR | [decocraft-rotation-temporaries](../optimizations/compatibility/decocraft-rotation-temporaries.md) |
| Whole-bake staging (`VertexData` ×4 + position) | 4 | not started | | |
| Deferred quarter-turn variants + compact snapshots (into `DecocraftRotatedQuadReuse`) | 5 | not started | | |
| 3D inventory icons (optional, default off) | 6 | not started | | |

Do not mark a row integrated until its PR is merged into `agent/integration-current`.

## Mobile evidence carried over (origin: Vivo/Mali/Java 21 unless stated)

- Face maps: 42,824 containers, 384 → 224 B each (≈ 6.5 MiB structural); mixed PSS, no clean PSS gain.
- Private directions: 964,046 avoided `Direction.values()` clones at cold start; host JDK 21 hot loop 40 → 0 B/call.
- Rotation temporaries: synthetic warmed paths 80 → 0 and 24 → 0 B/call (40 B fixed probe overhead removed); neither retained memory nor FPS.
- Whole bake: needed final output 192 B/quad; total 448 → 224 B/quad (−50 %), 224 B per call avoided; allocation only, no CPU claim.
- Combined PSS 5,128,462 / 4,421,645 / 3,624,295 KiB (control / patches / no Decocraft) is a mix of mechanisms and an interrupted control: not attributable to any one piece and not additive with geometry/atlas budgets.

# Decocraft compact structures: private direction array and ordered face maps

Status: **INTEGRATED (#377), default ON on PC (decision relayed by the coordinator session); hosted exact-pack on rev_7d5b9e50 and `latest`: menu reached, 2 ACTIVE, 0 FALLBACK**. Migrated from Android Support `824b37d` (`0.2.57-bake-staging`); migration ledger and evidence: [`docs/research/decocraft-support-migration-2026-10-16.md`](../../research/decocraft-support-migration-2026-10-16.md).

Both mechanisms are structural: they remove allocation or container overhead and change no model, quad, UV or render result. Neither claims a time-to-menu or FPS gain.

## Ownership and coexistence

BootOptim owns the Decocraft memory mechanisms. A legacy Android Support build that still carries the same mixin and runs on Android applies it itself; BootOptim then does not apply its own copy and logs `[WBOMBO-COMPAT] FALLBACK ... reason=legacy_support_owns_mechanism`, so nothing is applied twice. A mechanism in use sets `-Dwbombo.decocraft.<mechanism>=BootOptim` (verifiable capability) and logs `ACTIVE` at its first real use. On PC the legacy Support never applies these mixins (Android gate), so there is no overlap.

## Compatibility guard (code shape, not version)

`DecocraftContracts` reads the Decocraft class bytes from the discovered mod resources without initialising them and compares the SHA-256 of each touched method's instruction stream (`MethodContract`, ignores metadata, frames and unrelated methods) with `bootoptim-decocraft-contracts.properties`. No Decocraft version, JAR size or JAR hash is read. The mixin is not applied when a method differs or is missing; the target is re-checked in `preApply` (another mixin may have rewritten it); the mixin config plugin check disables the mechanism if an injector misses. Every case logs `[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=<id> mod=decocraft@<version> reason=<why>` and runs stock.

## Private direction array (`decocraft-private-directions`)

`BlockbenchBakery.calculateFacing` calls `Direction.values()` (a fresh 6-element clone) for every baked quad and only reads it. A `@WrapOperation` on that single call site returns one shared array, only while the method body matches the audited fingerprint (the loop never writes the array). The enum, its order and every other caller of `values()` are untouched.

- Kill switch: `-Dboot_optim.decocraftPrivateDirections=false`.
- Evidence (mobile, Vivo/Java 21, cold start): 964,046 clone requests avoided. Host JDK 21 hot-loop probe: 40 B per call → 0 (100,000 calls, 4,000,000 → 0 B). Allocation avoided is temporary garbage, not retained memory, CPU time or FPS.

## Ordered compact face maps (`decocraft-compact-face-maps`)

Each parsed Decocraft element holds its `faces` as a `LinkedHashMap` of up to six entries. At the return of both public `BBModelLoader.loadModel` entry points, before the caller can publish the model, the maps are replaced by `SmallOrderedMap`: same insertion order, null handling, mutability, live views, fail-fast iteration and stable entry objects. `Face`/UV/vector objects are not shared. The shape of the whole model (element, field, `LinkedHashMap`, `Direction` keys, `Face` values, ≤ 6 faces) is validated before the first write and each new map is compared with the old one; any mismatch leaves the model untouched and disables the mechanism.

- Kill switch: `-Dboot_optim.decocraftCompactFaceMaps=false`.
- Evidence (mobile): 42,824 maps / 256,944 entries; shallow container estimate 384 → 224 B per map (≈ 6.5 MiB structural, excludes shared keys and `Face` payload). The mobile paired run showed no clean PSS gain (mixed GC/residency); this is a representation reduction, not a proven process-memory or time saving.
- Host fixture (`./gradlew check` → `decocraftCompactStructuresContract`): 100,000 randomized operations against `LinkedHashMap` (order, views, nulls, compute/merge, iterator removal, fail-fast), `Face` identity, idempotence, atomic preflight with no partial write, and fingerprint tolerance. Stand-in classes live under `tools/research/` and are never packaged.

## PC validation

See the PR body: hosted exact-pack run with `exact-pack-fixture: latest` (functional gate: menu reached, `ACTIVE` lines, no new `FALLBACK`). Mobile numbers above are not PC numbers.

# Decocraft equal embedded-source Strings

Status: **candidate in PR**, default ON on PC (decision relayed by the coordinator session, 2026-10-16; kill switch kept). Migrated from Android Support `824b37d` (`memory.decocraftSourceStrings`). Ledger: `docs/research/decocraft-support-migration-2026-10-16.md`.

## Mechanism

Decocraft keeps a persistent cache of parsed models (`ModuleBlocks.MAT_TO_BB_MODEL`). Each model texture carries its PNG as an embedded `data:image/...;base64,` String in the public field `source`; equal images appear in many textures as separate String instances. Once, at the first fully loaded title screen (game load finished, no overlay, no world), `DecocraftSourceStrings` reads those fields through public reflection and points equal Strings (1 KiB – 4 MiB, `data:image/` + `;base64,`) at one canonical instance chosen from a map local to the pass.

Not shared, closed, freed or changed: the text itself, the model/texture/vector objects, atlases and images. Replacement is a compare-and-set, so a value the mod replaced concurrently is left alone. No `String.intern()` (Support interned; here the canonical instance is call-local and the map is dropped afterwards).

## Guards (data shape, not version)

Static public `MAT_TO_BB_MODEL` of type `Map`, exact `BBModel` class, public `textures` list, public non-final `String source` field on every texture class, ≤ 10,000 models, ≤ 4,096 textures per model, ≤ 20,000 textures, ≤ 64 Mi characters of unique text. The complete shape is validated before the first write. Any difference → no reference touched and `[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=decocraft-source-strings mod=decocraft@<version> reason=data_contract_unavailable_<exception>`; `ACTIVE` after a successful pass with the log line `BOOTOPTIM_DECOCRAFT_SOURCE_STRINGS source_fields= replaced_references= unique_characters_before= unique_characters_after= duration_ns=`.

Kill switch: `-Dboot_optim.decocraftSourceStrings=false`. Coexistence with a legacy Android Support that runs the same pass: harmless (the second pass finds equal instances and replaces nothing); no yield is needed.

## Evidence and limits

- Mobile (Vivo/Java 21, 654 common models): the Support registry reports character counts of String identities only, **not** retained bytes, RSS or a forced-GC result, and claims no process-memory saving for this pass.
- PC: the character/identity counts of the hosted run appear in the PR body. They describe model-field String identities only; backing arrays are not measured. No time claim (the pass is one bounded main-thread step at the menu; its `duration_ns` is logged).
- Host contract (`./gradlew check` → `decocraftSourceStringsContract`): exact text, sharing, repeated objects, idempotence, null/short preservation, preflight with no writes on a wrong or final field.

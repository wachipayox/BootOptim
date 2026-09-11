# Glowing Trim Armors offline custom-name migration — 2026-09-11

Status: **TECHNICALLY VIABLE FOR HASH-PINNED LOCAL GENERATION / NO-GO FOR REDISTRIBUTING A MODIFIED PACK WITHOUT AUTHOR PERMISSION / NOT A PRODUCTION CHANGE**

Authority/base: `agent/integration-current` @ `b3f0c5f6462a359483883741ac16d2f154868900`.

This Agent 128 lane investigates whether the exact `Glowing Trim Armors v5.0.zip` can be migrated offline from CITResewn's legacy `nbt.display.Name` compatibility syntax to the native 1.21 `minecraft:custom_name` component condition. It does not change BootOptim runtime behavior, the public exact-pack fixture, CIT selection, models, textures, pack ordering, or the production warning suppression added by PR #259.

## Current state and prior evidence

The public exact-pack fixture remains:

- release/tag `exact-pack-2026-09-02-v1`;
- asset `bootoptim-exact-pack.zip`;
- outer SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

The nested target identity established by PR #104 is:

- path `resourcepacks/Glowing Trim Armors v5.0.zip`;
- SHA-256 `06250ff06cb373fb203ae251975ac396f3cca7f3df80c2023c5607ca70874fb0`;
- size `13,429,945` bytes;
- `21,916` ZIP entries;
- `7,922` `.properties` files;
- exactly `7,920` active exact `nbt.display.Name` keys in `7,920` files;
- zero `nbt.display.Name.<suffix>` keys;
- zero pre-existing `components.minecraft:custom_name` keys.

PR #259, now integrated at this base, suppresses only the authorized emitted compatibility-warning family. It acts after parsing/conversion/message construction, so the `nbt.display.Name -> minecraft:custom_name` compatibility conversion remains present 7,920 times. Offline migration is therefore a materially different premise: remove the legacy parser branch itself rather than only its output event.

## Source-level equivalence

Historical CITResewn 1.21/1.21.1 source is unusually strong evidence because the legacy behavior is itself implemented as a conversion into the modern component route.

`ConditionComponents` registers aliases `components`, `component`, and `nbt`. For a legacy `nbt` rule whose metadata starts with `display.Name`, it replaces that metadata with:

```text
minecraft:custom_name + any suffix after display.Name
```

then logs the compatibility warning. The modern and legacy-converted forms then share the same code:

1. split the component id from metadata;
2. resolve `minecraft:custom_name` in the data-component registry;
3. retain the remaining metadata path;
4. set `matchValue = value.value()`;
5. create the same `ConditionNBT` fallback matcher with the same metadata path and value;
6. at test time read the same stack component; for `Text`, invoke the same text/string matcher first; otherwise encode the same component through its codec and test the same path.

The exact offline mapping is therefore:

```properties
nbt.display.Name=VALUE
```

into:

```properties
components.minecraft\:custom_name=VALUE
```

The namespace colon must be escaped in the physical `.properties` key. `PropertiesGroupAdapter` unescapes it before key parsing. No JSON wrapper belongs on `VALUE`: the legacy branch did not deserialize the RHS into a new text component; it passed the original `value.value()` into the modern component matcher. Rewriting a direct RHS to JSON would be an observable semantic change and is explicitly forbidden by the transformer.

## Matcher, escaping and text semantics

Both legacy-converted and native-modern forms feed the same `ConditionNBT` matcher. For the exact RHS bytes:

- direct values use exact string equality;
- `regex:` compiles a Java regular expression and requires whole-string `Matcher.matches()`;
- `iregex:` uses Java `CASE_INSENSITIVE | UNICODE_CASE`, also whole-string;
- `pattern:` uses CITResewn's full-string `*` / `?` wildcard matcher, including its backslash escaping behavior;
- `ipattern:` uses the corresponding case-insensitive wildcard path;
- `minecraft:custom_name` is a 1.21 `Text` component and both source forms enter the same `Text` matching path.

The transformation therefore must preserve every RHS byte after the parser's key/value boundary. That includes backslashes, Java-properties escapes such as `\u00A7`, literal UTF-8 `§`, regex metacharacters, wildcard syntax, whitespace, continuations, line endings, colors and arbitrary Unicode. The migration tool changes key bytes only and records an RHS SHA-256 for every changed rule.

The exact pinned pack has no suffix forms and no modern-key collisions. Although CITResewn source can mechanically map a `display.Name.<suffix>` to `minecraft:custom_name.<suffix>`, this tool deliberately rejects that changed input rather than broadening the proof beyond the audited hash.

## Reproducible offline tooling

This branch adds:

```text
scripts/exact-pack/migrate_glowing_trim_custom_name.py
scripts/exact-pack/test_migrate_glowing_trim_custom_name.py
```

The tool does not download or publish the third-party pack. It accepts only the exact filename, size, SHA-256 and structural counts above. Any drift is a hard failure.

`audit` verifies the source and reports rule/matcher/escape inventory. `transform` writes three separate outputs supplied by the operator:

1. a byte-exact backup of the original source ZIP;
2. a transformed candidate ZIP;
3. a JSON manifest containing source/output fingerprints and one semantic record per transformed rule.

The source is never edited in place. The manifest records per rule: resource path, physical line, old/new semantic key, old/new raw-key hashes, RHS hash/length, matcher class, and whether the RHS contains Unicode escapes, section-sign color data or backslashes.

For every transformed entry the tool performs an inverse reconstruction and requires byte identity with the original uncompressed payload. It also requires aggregate ordered-payload identity after inverse reconstruction. Untouched payloads must remain byte-identical. Entry order, filenames, timestamps, compression method, comments, extra fields, internal/external attributes, ZIP creator/extractor metadata and archive comment are checked for preservation. The generated ZIP's compressed-byte SHA is recorded but not treated as cross-environment semantic identity because recompression can depend on the Python/zlib environment, which is also written into the manifest.

Synthetic tests cover CRLF, `iregex:` + `\u00A7`, wildcard continuations, UTF-8 `§`, modern-key collision rejection, suffix rejection, comments, exact key unescaping, deliberate refusal to broaden into unaudited colon/whitespace separator forms, backup creation, manifest persistence, ZIP metadata, untouched binary payloads and exact inverse reconstruction.

## Historical exact-pack runtime gate (#104)

PR #104 already exercised the same key-only semantic migration against the exact nested target. Its hardened candidate smoke and later six-VM 3x3 retained the pack through final resource reload, reached menu, kept the expected 8,192 × 8,192 × 2 blocks atlas, produced zero BootOptim Mixin failures and had zero candidate legacy fallbacks. Candidate and control used the same repack writer, so ZIP recompression was not confounded with the key change.

The valid #104 3x3 did **not** establish a startup-performance win. Candidate TTMM median was `93.585 s` vs control `95.563 s` (`-1.978 s`), but sample dispersion was much larger and arithmetic means reversed sign; reload→FancyMenu showed the same problem. No laptop A/B was justified. Those measurements must not be reused as proof that removing the conversion saves ~2 seconds.

Since PR #259 now removes downstream log I/O while leaving parser conversion intact, a future performance experiment on a production-authorized migrated pack would test a narrower residual mechanism than #104: legacy-key normalization plus compatibility-message construction, not the already-suppressed appender output.

## License / redistribution gate

The current public CurseForge project for **Glowing Trim Armors** identifies the project license as **All Rights Reserved**. No separate permission found during this investigation grants BootOptim the right to redistribute a modified derivative of the resource-pack ZIP.

Accordingly:

- the transformer and manifest format can be published because they contain no third-party pack payload;
- a user can run the tool against the exact copy they already possess, subject to whatever rights apply to their use;
- this branch does **not** publish, commit, attach or upload the transformed ZIP;
- BootOptim must **not** ship the modified pack, replace the public exact-pack asset with a derivative, or distribute that derivative as part of a modpack until the pack author grants suitable permission or another clear distribution right is established.

This is a distribution conclusion, not a claim about every jurisdiction's private-copy rules. The safe project policy is local generation only until explicit permission exists.

## Required production-equivalence gate

The migration is not promoted merely because the parser route is source-equivalent. Before replacing the pack in a distributable exact modpack, all of the following are required:

1. **Distribution right:** explicit author permission or another verified right to distribute the modified derivative.
2. **Pinned transform:** exact source fingerprint, exact backup hash, generated candidate hash, and immutable per-rule manifest.
3. **Semantic diff:** exactly 7,920 rules differ; each differs only in the legacy→modern key; RHS hashes are identical; zero suffixes/collisions; inverse reconstruction of each changed payload and aggregate ordered payload matches source.
4. **Exact resource contract:** all 14/14 selected resource packs remain present and in identical order, exactly one valid final initial reload, no global pack-removal fallback, target pack retained, expected atlas dimensions, menu reached, zero BootOptim Mixin failures and zero `nbt.display.Name` fallback events.
5. **Representative CIT/model behavior:** derive test names from the actual RHS inventory, not invented examples. Cover positive and negative near-miss selectors, case-only negatives where appropriate, any actual regex/iregex/pattern/ipattern classes, all four armor slots, and representative early/middle/late families including diamond/amethyst, iron or diamond middle variants, netherite late variants, golden/sculk, iron/resin and the smaller gilded-blackstone/magma/sculk families.
6. **Visual/gameplay equivalence:** compare inventory icon and equipped armor output under identical camera/FOV/language/resource-pack order/settings; include bright and dark scenes to expose emissive/glow behavior; confirm fallbacks/weights/order select the same CIT/model for positive and negative cases.
7. **Only then performance A/B:** compare the authoritative migrated pack with its exact unmodified control under the same current BootOptim build, with PR #259 suppression behavior accounted for. Do not infer savings from warning counts or #104's noisy historical median.

Hosted CI can prove the resource contract and parser/model loading health, but llvmpipe is not sufficient evidence for final pixel/emissive equivalence. A visual gate remains necessary before production replacement.

## Decision

**Technical feasibility: GO, but only for the exact hash-pinned pack and local/offline generation.** The exact 7,920 rules are one-to-one expressible as native `minecraft:custom_name` conditions without translating JSON, matcher payloads, colors, regex, Unicode or paths. The source-level reason is stronger than a textual analogy: CITResewn's legacy branch itself normalizes into the same modern component matcher while preserving the original value.

**Distribution: NO-GO today.** The pack is published as All Rights Reserved and no derivative-redistribution permission was found. Do not commit or ship the transformed ZIP.

**Production replacement: NO-GO today.** Even with distribution permission, physical/representative CIT and visual equivalence has not been completed. This branch therefore contains tooling and research only. The existing PR #259 targeted warning suppression remains in place until a migrated pack becomes an explicitly authorized, versioned, fully validated pack input.

## Reopening / promotion criteria

Promote only after the distribution and equivalence gates above pass. If the source ZIP hash changes, any suffix/modern collision appears, CITResewn version semantics change, pack order changes, any selector/model differs, or any visual mismatch is found, regenerate the audit from the new input rather than weakening these guards.

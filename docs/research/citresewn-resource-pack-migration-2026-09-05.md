# CITResewn legacy custom-name resource-pack migration — 2026-09-05

Status: **REJECTED AS A BOOTOPTIM PERFORMANCE CANDIDATE / SEMANTIC MIGRATION PROVEN / PHYSICAL VISUAL GATE NOT RUN**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

PR: #104 (`codex/cit-resource-migration`).

This lane followed PR #97's diagnostic logging-pressure signal but did not reuse or ship its Log4j filter. It tested the actual source-level migration of one third-party resource pack in an ephemeral copy of the public exact-pack fixture. The migration itself is semantically and mechanically viable; the hardened hosted A/B did not validate a startup-performance benefit large enough to advance to physical timing.

## Exact target and identity contract

Outer fixture remained the immutable public pinned release asset:

- tag `exact-pack-2026-09-02-v1`;
- asset `bootoptim-exact-pack.zip`;
- SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

Hosted smoke verified the nested target exactly matches the supplied physical inventory:

- `resourcepacks/Glowing Trim Armors v5.0.zip`;
- SHA-256 `06250ff06cb373fb203ae251975ac396f3cca7f3df80c2023c5607ca70874fb0`;
- 13,429,945 bytes;
- 21,916 ZIP entries;
- 7,922 `.properties` entries;
- 7,920 active exact `nbt.display.Name` keys in 7,920 files;
- every matching RHS non-empty;
- zero legacy suffix-form keys;
- zero pre-existing modern `minecraft:custom_name` keys.

The helper fails before writing a variant if the source hash, size, counts, duplicate-entry assumptions, suffix assumptions, or collision assumptions differ.

## Exact CITResewn equivalence

CITResewn's 1.21/1.21.1 `ConditionComponents.load` accepts `components`, `component`, and legacy `nbt`. For legacy `nbt.display.Name...`, the compatibility path replaces `display.Name` with `minecraft:custom_name`, preserves `value.value()`, emits the legacy warning, then continues through the same component-type lookup and `ConditionNBT` matcher used by the modern form.

The canonical candidate therefore changes only:

```properties
nbt.display.Name=VALUE
```

into:

```properties
components.minecraft\:custom_name=VALUE
```

The bytes after `=` remain untouched. This is intentional: do not rewrite a plain value as JSON, normalize Unicode, change case, alter escaping, or simplify a matcher while performing this migration.

Relevant matcher behavior remains the same on both paths:

- direct values are exact string matches;
- `regex:` uses Java regex whole-string matching;
- `iregex:` uses case-insensitive Unicode-aware Java regex matching;
- `pattern:` uses CITResewn's full-string `*` / `?` wildcard matcher;
- `ipattern:` is the corresponding case-insensitive wildcard matcher;
- custom-name `Text` is matched through the same `ConditionNBT` text/string path on both legacy-converted and modern-component forms.

Java-properties syntax matters: the namespace colon is escaped on disk as `minecraft\:custom_name`. The migration helper is byte-oriented around the property key/value boundary instead of loading and reserializing the entire file through `java.util.Properties`, so escapes, UTF-8, line endings, continuations, ordering, duplicate-value behavior, comments, and matcher payloads are not normalized accidentally.

The exact pinned pack contains zero `nbt.display.Name.<suffix>` keys. If a later pack contains suffixes, CITResewn's compatibility mapping makes the mechanical metadata equivalent `minecraft:custom_name.<suffix>`, but that is a changed input and must be re-audited rather than assumed safe under this hash pin.

A pre-existing modern target key is treated as a hard collision. The helper rejects it rather than relying on property priority or a last-value-wins assumption. Unexpected duplicate ZIP entry names are rejected for the same reason.

## Hash-pinned symmetric repack

`scripts/exact-pack/migrate_cit_resource_pack.py` builds both variants from the same original nested ZIP:

- **control:** repacks every entry through the same writer without changing uncompressed payload bytes;
- **candidate:** uses the same writer and changes exactly the 7,920 legacy key byte ranges.

The original release asset and extracted original are never modified. The transformed ZIP exists only under the runner's ephemeral exact-pack copy and keeps the same resource-pack filename and selected-pack position. No generated third-party ZIP is uploaded as a workflow artifact.

For both variants the helper preserves, where represented by Python's ZIP API:

- entry order and filenames;
- timestamp fields;
- compression method;
- comments and extra fields;
- internal/external attributes;
- creator/extractor metadata;
- archive comment.

It does **not** claim compressed-stream byte identity because Python recompresses entries. That is why the control is repacked by the identical path: the A/B never compares an original compressed ZIP directly with a newly compressed candidate.

Integrity proof from smoke and all valid A/B runs:

- source SHA-256: `06250ff06cb373fb203ae251975ac396f3cca7f3df80c2023c5607ca70874fb0`;
- control repack SHA-256: `8ff0a12eeb88707603851caedd585df7040df8883e906451133bc41a3a719f2a`;
- control size: 12,937,705 bytes;
- candidate SHA-256: `0ab526d5a2ca92b9d3ccdb1fe889c558752afb86bb8853cbddf2f3ac50198647`;
- candidate size: 13,013,298 bytes;
- changed candidate entries: 7,920;
- untouched candidate entries: 13,996;
- candidate legacy/modern keys: 0 / 7,920;
- control legacy/modern keys: 7,920 / 0;
- ordered original payload SHA-256: `bbb2993e6e207acbd4ff5600ef73ab87df6b3f070130613d255384b032b4b06e`;
- candidate inverse-reconstructed ordered payload SHA-256: the same `bbb2993e...` digest;
- reverse payload mismatches: 0;
- untouched payload mismatches: 0;
- checked ZIP metadata mismatches: 0;
- RHS bytes preserved: true;
- entry order preserved: true;
- archive comment preserved: true.

Synthetic tests additionally cover comments, line endings, continuations, wildcard payloads, UTF-8 / `\uXXXX` content, collision rejection, symmetric reconstruction, and byte-exact RHS preservation.

## Hosted execution contract

The exact-pack workflow gained an opt-in fixture transform:

```text
exact-pack-fixture-transform: cit-resource-migration
```

Without that directive the normal exact-pack JVM-argument contract is unchanged. With it, A/B is allowed to keep candidate/control JVM args identical because the isolated variable is the ephemeral nested resource-pack copy.

No BootOptim runtime code, CITResewn runtime code/config, JVM flags, OS configuration, MCEF setting, or selected-pack ordering differs between control and candidate.

### Hardened post-launch gate

The final gate requires every timed VM to prove all of the following:

1. source integrity report begins from all 7,920 expected legacy rules;
2. target pack is present in the **final** `Reloading ResourceManager` line, not merely an earlier attempt;
3. Minecraft did not log `Caught error loading resourcepacks, removing all selected resourcepacks`;
4. candidate has 7,920 modern keys and zero legacy keys; control has the inverse counts;
5. candidate emits zero `Using legacy nbt.display.Name` fallback events; control emits exactly 7,920 in both captured logs;
6. candidate does not report an unknown `minecraft:custom_name` component type;
7. inverse payload reconstruction and all ZIP integrity invariants pass;
8. `reload_to_fancymenu_finish_ms` and panorama timing are non-null;
9. blocks atlas remains exactly 8,192 × 8,192 × 2;
10. title is reached and BootOptim Mixin errors remain zero.

This hardening matters because the first A/B campaign exposed a real false-positive path in the earlier validator.

## Candidate smoke — valid mechanism proof

Run: `33959606640`.

The smoke passed source hash/count checks, transformed exactly 7,920 entries, reached title with the pack present, emitted zero candidate legacy fallbacks, preserved an 8,192² atlas, and reported zero BootOptim Mixin errors.

Smoke timing was:

- TTMM: 71.671 s;
- reload→FancyMenu: 33.856 s.

Those numbers are a mechanism smoke only and are not used as an A/B performance result.

## First 3×3 — invalidated, not evidence

Run: `33959834030`.

Candidate-3 hit missing XercaPaint layer definitions. Minecraft then logged `Caught error loading resourcepacks, removing all selected resourcepacks`, reloaded only a reduced resource set, produced a 4,096² blocks atlas, and never produced a valid reload→FancyMenu marker. The original validator had only checked whether `Glowing Trim Armors v5.0.zip` appeared in *some* `Reloading ResourceManager` line, so the bad VM falsely passed.

That invalidates the full first campaign. In particular, its apparent aggregate candidate TTMM median of 54.274 s versus 94.532 s control, and the derived `-40.258 s`, are **not migration evidence** and must not be quoted as a speedup.

The other candidate observations in that invalid campaign were also extremely dispersed, reinforcing that the result could not be salvaged by simply deleting candidate-3 after the fact.

The hardened gate above was added before repeating the experiment.

## Hardened hosted 3×3 — final valid measurement

Run: `33960265618`.

Summary artifact: `9967812422`.

All six fresh VMs passed the hardened resource-pack gate. All retained the target pack through the final reload, retained the 8,192 × 8,192 × 2 atlas, produced full reload metrics, and had zero BootOptim Mixin errors.

### Per-run measurements

| Variant | Iteration | TTMM ms | reload→FancyMenu ms | mod entrypoint ms | post-mod ms | MCEF ms | panorama ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| control | 1 | 80,049 | 36,849 | 26,420 | 53,629 | 1,317 | 3,539.056 |
| control | 2 | 95,617 | 43,343 | 32,066 | 63,551 | 2,300 | 4,566.717 |
| control | 3 | 95,563 | 43,668 | 32,503 | 63,060 | 2,429 | 4,304.576 |
| candidate | 1 | 96,599 | 42,614 | 32,404 | 64,195 | 3,271 | 4,065.361 |
| candidate | 2 | 93,585 | 43,002 | 31,446 | 62,139 | 1,272 | 4,498.046 |
| candidate | 3 | 88,100 | 40,837 | 29,992 | 58,108 | 1,002 | 3,894.961 |

Official aggregate medians:

| Metric | control | candidate | candidate - control |
| --- | ---: | ---: | ---: |
| TTMM | 95.563 s | 93.585 s | -1.978 s (-2.07%) |
| reload→FancyMenu | 43.343 s | 42.614 s | -0.729 s (-1.68%) |
| mod entrypoint | 32.066 s | 31.446 s | -0.620 s (-1.93%) |
| post-mod | 63.060 s | 62.139 s | -0.921 s (-1.46%) |

MCEF and panorama medians also happened to move, but this resource-key migration has no source-level mechanism for those movements; they are treated as runner/startup noise rather than attributed savings.

### Dispersion and decision

The favorable median direction is not enough to validate the performance hypothesis:

- control TTMM values: 80.049, 95.617, 95.563 s;
- candidate TTMM values: 96.599, 93.585, 88.100 s;
- control TTMM sample standard deviation: ~8.97 s;
- candidate TTMM sample standard deviation: ~4.31 s;
- control TTMM range: 15.568 s;
- candidate TTMM range: 8.499 s;
- control mean TTMM: ~90.410 s;
- candidate mean TTMM: ~92.761 s, i.e. candidate is ~2.352 s slower by the mean.

For reload→FancyMenu:

- control values: 36.849, 43.343, 43.668 s;
- candidate values: 42.614, 43.002, 40.837 s;
- control sample standard deviation: ~3.85 s;
- candidate sample standard deviation: ~1.15 s;
- control range: 6.819 s;
- candidate range: 2.165 s;
- control mean: ~41.287 s;
- candidate mean: ~42.151 s, i.e. candidate is ~0.864 s slower by the mean.

Candidate and control ranges overlap heavily, the nominal median effect is much smaller than control dispersion, and the arithmetic means reverse sign. There is no candidate dominance. Therefore the hosted gate does **not** validate a BootOptim startup win.

PR #97's valid logging-filter A/B (`33927602940`) had a `-2.149 s` median TTMM result. The superficially similar `-1.978 s` migration median here is not treated as confirmation: the hardened migration experiment is too noisy and does not reproduce an unambiguous effect.

### Performance disposition

- Do **not** request laptop A/B from this result.
- Do **not** promote the resource-pack migration as a BootOptim performance optimization.
- Do **not** ship #97's logging filter as a substitute.
- Reopen the performance premise only with materially new evidence, for example a lower-noise harness or direct phase attribution showing that legacy conversion/log construction itself is a stable critical-path cost beyond normal runner spread. The continued existence of 7,920 rules alone is not a new premise.

## Physical visual corpus if the owner later migrates for compatibility/cleanliness

Hosted CI proves parser/matcher compatibility and resource-pack participation, not pixel identity. A production replacement of the third-party pack would still require a physical visual gate even though the BootOptim performance lane is closed.

The exact path inventory contains the four armor bases observed in the migrated CIT tree (`diamond`, `iron`, `golden`, `netherite`), all four armor slots, and trim families including `amethyst`, `cherry`, `copper`, `diamond`, `emerald`, `gold`, `iron`, `lapis`, `netherite`, `prismarine`, `quartz`, `redstone`, `resin`, `gilded_blackstone`, `magma`, and `sculk`.

A concrete visual corpus should include at minimum:

1. `diamond/amethyst/a1` helmet, chestplate, leggings, and boots — the first observed family/variant, including paths such as `items/armor_items/diamond/amethyst/a1/dhelmet.properties`;
2. one middle variant from diamond or iron using the exact positive renamed item string from its property RHS;
3. one late/final variant from netherite, all four slots where available;
4. `golden/sculk` and `iron/resin` representatives;
5. one representative each from the smaller `gilded_blackstone`, `magma`, and `sculk` families;
6. for each chosen selector: exact positive name plus a negative near-miss; where the actual matcher is case-sensitive, add a case-only negative;
7. inventory icon and equipped third-person armor output;
8. controlled bright and dark lighting to expose emissive/glow differences;
9. identical camera, FOV, pack order, language, shaders/visual settings, and item data for control/candidate screenshots.

If any sampled actual RHS uses `pattern:`, `ipattern:`, `regex:`, `iregex:`, or a translatable custom-name Text, include at least one positive/negative pair for that exact matcher class and relevant client language. Do not invent test names; derive them from the real property RHS.

Because the hosted performance premise failed, this visual corpus is documentation for a possible owner-driven compatibility cleanup, not a request to start physical performance timing.

## Final conclusion

The exact migration is **semantically viable and mechanically reversible** for the pinned `Glowing Trim Armors v5.0.zip`. It removes all 7,920 legacy fallback diagnostics without changing RHS payloads or any other resource-pack content at the uncompressed-entry level. However, the valid hardened 3×3 does **not** establish a startup-performance benefit beyond hosted variance. The BootOptim performance front is therefore **REJECTED** on current evidence, no laptop A/B is requested, and no production resource-pack replacement is authorized by this research alone.

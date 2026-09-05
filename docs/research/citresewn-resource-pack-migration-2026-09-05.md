# CITResewn legacy custom-name resource-pack migration — 2026-09-05

Status: **ACTIVE DIAGNOSTIC / HOSTED SMOKE REQUIRED / VISUAL GATE PENDING**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

This lane follows PR #97's diagnostic logging-pressure result but does not reuse or ship its Log4j filter. The experiment changes only an ephemeral copy of the public exact-pack fixture and measures a source-level resource-pack migration.

## Exact target and identity contract

Outer fixture remains the public pinned release asset:

- tag `exact-pack-2026-09-02-v1`;
- `bootoptim-exact-pack.zip`;
- SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

Nested target must be exactly:

- `resourcepacks/Glowing Trim Armors v5.0.zip`;
- SHA-256 `06250ff06cb373fb203ae251975ac396f3cca7f3df80c2023c5607ca70874fb0`;
- 13,429,945 bytes;
- 21,916 ZIP entries;
- 7,922 `.properties` entries;
- 7,920 active exact `nbt.display.Name` keys in 7,920 files;
- zero pre-existing modern `components.minecraft:custom_name` keys or suffix variants.

The migration helper fails before producing a variant if any identity/count/collision invariant differs.

## Exact CITResewn equivalence

CITResewn's 1.21/1.21.1 `ConditionComponents.load` accepts `components`, `component`, and legacy `nbt`. For legacy `nbt.display.Name...`, it constructs metadata `minecraft:custom_name...` while preserving `value.value()` and then emits the compatibility diagnostic. Both legacy and modern forms continue through the same component-type resolution and the same `ConditionNBT` matcher.

Therefore the candidate transforms only the property key:

```properties
nbt.display.Name=VALUE
```

into:

```properties
components.minecraft\:custom_name=VALUE
```

The bytes after the `=` are left untouched. This preserves direct strings, `pattern:`, `ipattern:`, `regex:`, `iregex:`, UTF-8 content, Java-style escapes, continuation bytes, and case semantics. Suffixes are mapped mechanically if encountered, but the pinned pack is required to contain zero suffix-form keys. A pre-existing target modern key is a hard collision and rejects the build instead of relying on duplicate-key priority.

CITResewn's `PropertyGroup` uses insertion-ordered sets and can retain multiple property values. The pinned experiment therefore rejects unexpected duplicate ZIP entry names and unexpected key counts rather than assuming Java `Properties` last-value semantics.

## Repack design

`scripts/exact-pack/migrate_cit_resource_pack.py` builds both variants from the same original nested ZIP:

- **control**: repacks every entry through the same writer without changing payload bytes;
- **candidate**: uses the identical writer and changes only the 7,920 key byte ranges.

For both variants the helper preserves entry order, filename, timestamps, compression method, comments, extra fields, internal/external attributes, creator/extractor metadata, and archive comment. Compressed byte streams are not claimed identical because Python's ZIP writer recompresses entries; this is why the control is repacked by the same path.

Integrity gates prove:

- every untouched entry has byte-identical uncompressed payload;
- candidate RHS bytes are untouched because only the key span is replaced;
- inverse replacement reconstructs each changed source payload exactly;
- an ordered aggregate digest of reconstructed payloads equals the original aggregate digest;
- metadata fields checked above have zero mismatches.

No generated third-party ZIP is uploaded as an artifact. Only the compact JSON integrity report is retained.

## Hosted execution contract

The exact-pack workflow gains an opt-in directive:

```text
exact-pack-fixture-transform: cit-resource-migration
```

Without it, the normal JVM-argument A/B contract is unchanged. With it:

- smoke builds the candidate resource pack;
- A/B permits identical JVM args because the fixture copy itself is the isolated variant;
- A/B control and candidate each run on fresh hosted VMs;
- both variants retain the same pack filename and `options.txt` position;
- no BootOptim/CIT runtime code, JVM, OS, MCEF, or user config setting differs between variants.

Post-launch validation requires:

1. `file/Glowing Trim Armors v5.0.zip` appears in `Reloading ResourceManager`;
2. source report proves all 7,920 rules existed before repack;
3. candidate report has 7,920 modern keys and zero legacy keys, with exact reverse reconstruction;
4. candidate `latest.log` and console contain zero `Using legacy nbt.display.Name` fallback events;
5. control contains exactly 7,920 such events in both logs;
6. candidate does not report unknown `minecraft:custom_name` component type;
7. the existing title and BootOptim Mixin gates remain intact.

Hosted CI does not prove rendered visual identity. Physical visual validation remains required before any resource-pack replacement is considered production.

## Performance interpretation

PR #97's valid filter A/B (`33927602940`) measured median TTMM 90.226 s candidate vs 92.375 s control and reload→FancyMenu 41.418 s vs 42.443 s. That result is a logging-pressure signal only. This migration experiment must be judged independently and must not inherit the full 2.149 s delta as expected savings.

A hosted win is useful only if TTMM and reload wall move coherently while every migration gate passes. Hosted failure/tie closes the performance premise without requesting a laptop A/B. A coherent hosted result can advance to a physical visual gate and, if the effect remains material/hardware-sensitive, later real-hardware timing.

## Physical visual corpus after hosted validation

Validate both item icon and equipped armor rendering, including emissive/glow behavior, for a concrete matrix sampled from the pack paths and RHS inventory:

- material bases: diamond, netherite, iron, gold, chainmail and leather when present;
- trim families including at least amethyst and sculk (both are visible in current exact-pack CIT paths), plus first/middle/last lexicographic families from the generated manifest;
- all four slots: helmet, chestplate, leggings, boots;
- one direct-string rule, one `pattern:`/`ipattern:` rule if present, and one `regex:`/`iregex:` rule if present;
- positive exact-name matches, near-miss case changes where the matcher is case-sensitive, and negative names;
- inventory, hand/third-person where applicable, equipped armor, bright and dark lighting, and any emissive layer used by the pack.

The exact RHS/matcher inventory will be taken from the candidate integrity report/artifact before physical validation; no names should be invented manually when the real rule corpus is available.

## Current gate

Implementation is diagnostic only. First allowed runtime action is one hosted candidate smoke. Only if it proves nested ZIP identity, transformation integrity, target-pack participation, zero candidate legacy fallback, and title completion may the PR be edited to request hosted 3×3 A/B.

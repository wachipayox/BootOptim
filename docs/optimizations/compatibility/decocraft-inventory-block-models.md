# Decocraft 3D inventory icons (optional, appearance change)

Status: **candidate in PR, default OFF, visual approval pending**. Enable with `-Dboot_optim.decocraftInventoryBlockModels=true`. Migrated from Android Support `824b37d` (`inventory.decocraftBlockModels`, default false). The user decided that on PC this stays optional because it changes how items look. Ledger: `docs/research/decocraft-support-migration-2026-10-16.md`.

## What changes for the player

Decocraft's decoration items (about 3,200 in the reference pack) are drawn as flat 2D icons. With the switch on, an eligible item is drawn as the decoration's own 3D block model (the one the placed block uses), scaled to fit the slot and turned 30°/225° in GUI. Everything else (tools, odd shapes, other packs) keeps its icon. Placed blocks, collisions and gameplay are not touched.

## Mechanism

- `ModelManagerDecocraftInventoryMixin` wraps `ModelManager.reload` and gives it a per-reload `ResourceManager` overlay (`DecocraftInventoryPatch`). The original packs, resource manager and mod files stay untouched; the overlay is dropped on the next reload.
- A `decocraft:models/item/*.json` is replaced only if it is exactly `{parent: item/generated, textures: {layer0: decocraft:item/…}}`, the same-name `decocraft:models/block/*.json` comes from the same pack (whose id contains `decocraft`) and declares `loader: decocraft:bbmodel` with `decoref`, and the same-name icon PNG exists in the same pack. The replacement is `{parent, loader: boot_optim:deco_inventory, block}`; its geometry loader accepts only `decocraft:block/*`.
- `DecocraftInventoryModel` wraps the canonical cached bake of the block model (`baker.bake(block, X0_Y0)`); quads are not cloned. A `minecraft:filter` source appended to the block atlas excludes the icons that became unreferenced; any icon named by an unchanged model stays. Caps: 4,096 candidates, 4 MiB per JSON.
- Anything unexpected (shape, provenance, limits, exceptions) leaves the stock resource manager and logs `[WBOMBO-COMPAT] FALLBACK mechanism=decocraft-inventory-block-models`; success logs `ACTIVE` and `BOOTOPTIM_DECOCRAFT_INVENTORY inventory_models= excluded_icon_sprites=`. No Decocraft version, JAR size or hash is read.

## Relation to the rejected experiment

[`decocraft-3d-item-sprite-elision-2026-09-02`](../../research/decocraft-3d-item-sprite-elision-2026-09-02.md) (PR #79) remapped parsed models to a parent-only block model gated on exact Decocraft 3.0.11 and a 63-entry keep-list, and removed sprites by intercepting `SpriteSource.Output.add`; it tied on the laptop and regressed on the hosted runner, so it was rejected as a startup optimisation. This mechanism is different (resource overlay + canonical-model wrapper + standard atlas filter, shape/provenance guards, no version or keep-list), and it is **not** offered as a startup optimisation: no time claim, default off. Reopening criterion for a startup claim: a physical-PC paired run showing the model/atlas segment on the critical path.

## Evidence and limits

- Mobile (Vivo/Java 21, Support registry): previews of six inventory views passed; the atlas shrinks by the excluded icons. Memory effect on PC is unmeasured here (the hosted job reports the atlas size but not heap).
- Hosted exact-pack A/B in the PR (candidate on vs off) checks the mechanism runs and the menu is reached, and reports the atlas dimensions. **CI cannot judge the look**: a visual review of the inventory (and creative tab, item frames, hand/ground rendering) by the user is required before any default change.

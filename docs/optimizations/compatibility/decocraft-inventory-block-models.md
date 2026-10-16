# Decocraft 3D inventory icons (optional, appearance change)

Status: **candidate in PR, default OFF, visual review passed on PC (2026-10-16, user: icons look right, no bad visible differences)**; stays opt-in by the user decision, no RAM claim. Enable with `-Dboot_optim.decocraftInventoryBlockModels=true`. Migrated from Android Support `824b37d` (`inventory.decocraftBlockModels`, default false). The user decided that on PC this stays optional because it changes how items look. Ledger: `docs/research/decocraft-support-migration-2026-10-16.md`.

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

## PC visual review plan (ready to run, needs the shared PC)

Goal: the user decides from side-by-side screenshots whether the 3D icons are acceptable. No timing or memory claim comes from this run.

1. Same instance, Beta revision pinned for both arms (`PACK.md`), BootOptim CI JAR of this PR in `mods/`, `startupLog=true` is not needed.
2. Arm A (control, stock): launch with nothing extra. Arm B: add the JVM flag `-Dboot_optim.decocraftInventoryBlockModels=true` (Wachiland Launcher instance `jvm_flags`). Confirm in `latest.log`: `[WBOMBO-COMPAT] ACTIVE ... decocraft-inventory-block-models` and `BOOTOPTIM_DECOCRAFT_INVENTORY inventory_models≈3218 excluded_icon_sprites≈3205`. A `FALLBACK` line means the overlay did not apply: stop and report it.
3. Window 1920x1080, GUI scale 3, the same creative test world in both arms, `F2` screenshots (kept in `screenshots/`, same file names per arm: `A-<n>.png` / `B-<n>.png`).
4. Shots, in this order: (1) Decocraft creative tab, first page; (2) a middle page and the last page; (3) search `chair`, `table`, `lamp`, `plant`, `sign` in the creative search tab (one shot each); (4) hotbar with 9 different Decocraft items selected one by one; (5) the item held in the main hand, first person and third person (`F5`); (6) a dropped item on the ground (`Q`), two metres away; (7) an item frame holding one item; (8) a non-Decocraft control (a diamond sword and a vanilla block) to confirm nothing else changed.
5. Look for: items that appear too large, too small, clipped by the slot, floating off-centre, upside down or black; missing or purple textures; icons of tools/odd Decocraft items that must stay flat; a flat icon of any *placed-block* item that should now be 3D; changed vanilla icons (must be none).
6. Report the pair of folders; do not change the default from this run alone. Reopening the startup question needs a separate physical paired run (see "Relation to the rejected experiment").

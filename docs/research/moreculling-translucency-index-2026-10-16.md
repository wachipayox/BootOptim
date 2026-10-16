# MoreCulling translucency index — 2026-10-16

## Premise

MoreCulling (validated 1.0.8, not version-pinned) resets each `SimpleBakedModel`'s translucency
cache on every resource reload (`SimpleBakedModel_cacheMixin.moreculling$resetTranslucencyCache`,
sliced on the render thread by RRLS menu-ready). For each quad it calls
`SpriteUtils.doesHaveTranslucency(image, null, x0, x1, y0, y1)`, which scans the UV rectangle
pixel by pixel until it finds alpha ≠ 255. Most block textures are fully opaque, so every call is
a full scan, repeated for every quad that shares the sprite.

Evidence (fast PC, JFR, same run as
[fml-event-trace-producer-filter](fml-event-trace-producer-filter-2026-10-15.md)): ~13 % of
render-thread samples in the post-menu reload tail and ~19 % of the final F3+T freeze have
`SpriteUtils.doesHaveTranslucency` under `moreculling$resetTranslucencyCache`.

Prior work: RRLS experiment `MoreCullingPixelsMixin` (private fork, 2026-09-27) memoized
rectangles and built an alpha index only after proving opacity, pinned to MoreCulling 1.0.8;
never integrated. This mechanism replaces it with a version-independent BootOptim one.

## Mechanism

`MoreCullingTranslucencyIndex` (default on, `-Dboot_optim.moreCullingTranslucencyIndex=false`
restores stock): per image (weak identity key), one summed-area table of non-opaque pixels
(images ≤ 512×512; fully opaque images store a shared marker), answering any rectangle in O(1).
Only the `orMatch == null` form; layered matching, out-of-range rectangles and images without
alpha stay stock. Differential guard: the first 2 048 answers and 1 in 512 afterwards are compared
with MoreCulling's own result; any mismatch disables the mechanism (`[WBOMBO-COMPAT] FALLBACK`).

## Fast-PC A/B (2026-10-16)

Origin: fast PC, Wachiland Launcher, Beta rev 3, RRLS diagnostic `0fc8475` (MENU_READY,
automatic manual reload on the title), BootOptim integration `3a29517` + this branch (FML TRACE
filter #367 and block-shape memo #369 active in both arms). Order: primer, control, candidate,
candidate, control. Endpoints as in the FML entry (RRLS frame watchdog ≥ 75 ms).

| Run | `main_menu` ms | Tail freezes n / sum / max | F3+T ms | F3+T freezes n / sum / max |
|---|---|---|---|---|
| control 1 | 52 406 | 12 / 1 405 / 260 | 11 884 | 3 / 2 512 / 2 114 |
| control 2 | 52 658 | 8 / 1 097 / 255 | 11 035 | 5 / 2 632 / 2 093 |
| candidate 1 | 52 313 | 9 / 1 071 / 255 | 10 955 | 4 / 2 360 / 1 919 |
| candidate 2 | 51 942 | 10 / 1 528 / 286 | 10 874 | 4 / 2 269 / 1 803 |

Candidates: `[WBOMBO-COMPAT] ACTIVE`, no FALLBACK (differential guard: 0 mismatches).

- Segment: the final F3+T freeze (where MoreCulling's sliced listener runs back-to-back) is
  shorter in both candidates than in both controls (max −0.24 s, sum −0.26 s median); F3+T total
  −0.55 s median (11.46 → 10.91 s), both candidates below both controls.
- Post-menu tail freezes: mixed, no effect claimed (MoreCulling is sliced across frames there).
- `main_menu`: within noise.

## Decision

Integrate: small but consistent segment win, guarded, no pinning. Supersedes the RRLS
`MoreCullingPixelsMixin` experiment (to be removed from the RRLS production base).

## Reopening

A FALLBACK (guard mismatch) or a MoreCulling change that stops calling this method.

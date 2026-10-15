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

## Results

Pending: hosted exact-pack A/B (segment: MoreCulling listener in the startup reload) and the
fast-PC A/B (needs the user's go-ahead: the PC is shared with other agents).

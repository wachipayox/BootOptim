# Mod-specific startup compatibility

These optimizations exist because profiling attributed a substantial startup cost to a specific third-party implementation and a narrow, fail-safe optimization was possible.

- [FancyMenu panorama preload overlap](fancymenu-panorama-preload.md)
- [Decocraft quarter-turn geometry reuse](decocraft-quarter-turn-reuse.md)

Rules for this category:

1. No hard dependency on the target mod.
2. Exact target behavior/version assumptions must be documented.
3. Unknown or changed behavior must fall back to the mod's original implementation.
4. A kill switch must be available for pack maintainers.
5. The optimization must be justified by real-pack profiling rather than by mod popularity alone.

# BootOptim optimization catalog

This directory documents every production startup optimization in BootOptim. The catalog is split by scope:

- [`global/`](global/) contains optimizations for shared Minecraft, NeoForge or ModLauncher/FML paths.
- [`compatibility/`](compatibility/) contains narrowly targeted optimizations for a specific third-party mod when profiling proves that mod owns a meaningful startup bottleneck.

Each document records the bottleneck, implementation, safety invariants, fallback/kill switch, resource trade-offs, and measured results. Measurements are evidence for the tested environment, not promises for every modpack.

## Production optimization matrix

| Optimization | Scope | Default | Primary expected effect |
| --- | --- | --- | --- |
| [Persistent mod scan cache](global/mod-scan-cache.md) | Global / FML mod metadata scanning | Enabled | Avoid repeated ASM metadata scanning on warm launches |
| [Asynchronous scan-cache writes](global/async-scan-cache-write.md) | Global / cache persistence | Enabled with scan cache | Keep cold-cache persistence I/O off FML scan workers |
| [Vanilla blockstate bake identity reuse](global/blockstate-bake-dedup.md) | Global / client model bake | Enabled | Remove redundant top-level `MultiVariant`/`MultiPart` bakes |
| [FancyMenu panorama preload overlap](compatibility/fancymenu-panorama-preload.md) | FancyMenu 3.9.x | Enabled when target exists | Overlap already-asynchronous PNG decoding instead of serial waiting |
| [Decocraft quarter-turn geometry reuse](compatibility/decocraft-quarter-turn-reuse.md) | Decocraft 3.0.11-compatible BBModel path | Enabled when exact guarded path matches | Derive horizontal rotations from one authoritative Blockbench bake |

## Safety policy

Production optimizations should be fail-open toward the original implementation: if BootOptim cannot prove that its optimized path is applicable, it executes the original Minecraft/NeoForge/mod behavior. Third-party compatibility hooks are optional and must not create a hard runtime dependency on the target mod.

Performance work that changes semantics, globally reduces concurrency, or only looks faster in a subphase while regressing time-to-main-menu is not promoted merely because a microbenchmark improves.

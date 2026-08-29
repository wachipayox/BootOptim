# BootOptim

BootOptim is a NeoForge 1.21.1 performance mod focused specifically on reducing the time from process launch to the Minecraft main menu, with compatible server-side startup improvements where they are worthwhile.

## Goals

- Measure startup phases before optimizing them.
- Target improvements large enough to matter in real modpacks, especially expensive shared paths such as mod discovery/scanning, resource loading, model loading, and lifecycle work.
- Preserve compatibility with large mixed NeoForge/Sinytra/Create-based packs.
- Scale sensibly across both high-core-count and resource-constrained systems instead of assuming that more parallelism is always faster.
- Avoid duplicating dedicated optimization mods unless BootOptim can demonstrably replace them with a better implementation.
- When a multipurpose mod contains an overlapping optimization, prefer disabling only the overlapping feature while keeping the rest of that mod active.

## Development baseline

- Minecraft 1.21.1
- NeoForge 21.1.248
- Java 21
- Mod id: `boot_optim`
- Author: Wachipayoxx

The project starts intentionally minimal. Profiling hooks and optimizations are added only when they can be benchmarked and isolated.

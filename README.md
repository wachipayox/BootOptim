# BootOptim

BootOptim is a NeoForge 1.21.1 performance mod focused specifically on reducing the time from process launch to the Minecraft main menu, with compatible server-side startup improvements where they are worthwhile.

## Goals

- Measure startup phases before optimizing them.
- Target improvements large enough to matter in real modpacks, especially expensive shared paths such as mod discovery/scanning, resource loading, model loading, and lifecycle work.
- Preserve compatibility with large mixed NeoForge/Sinytra/Create-based packs.
- Scale sensibly across both high-core-count and resource-constrained systems instead of assuming that more parallelism is always faster.
- Avoid duplicating dedicated optimization mods unless BootOptim can demonstrably replace them with a better implementation.
- When a multipurpose mod contains an overlapping optimization, prefer disabling only the overlapping feature while keeping the rest of that mod active.

## Startup diagnostics

BootOptim creates `config/boot_optim.properties` on first launch. The startup report is disabled by default:

```properties
startupLog=false
```

Set it to `true` to write a lightweight report to `logs/bootoptim-startup.log`. Development/IDE runs force this report on automatically. The report records the BootOptim version, optimization enable/disable decisions and reasons, cache/version events, recoverable failures reported by BootOptim components, important startup milestones, and total JVM uptime when the main menu is first reached.

This lightweight report does **not** enable JFR or the fine-grained startup profiler. Heavy profiling remains opt-in through the existing debug/benchmark tooling.

## Persistent cache

The current mod metadata scan cache is stored under:

`<game directory>/.bootoptim/mod-scan-cache-v1/`

BootOptim also stores `<game directory>/.bootoptim/cache-version.txt`. When the BootOptim mod version changes, the persistent cache namespace is invalidated before it can be reused. The mod version is additionally stamped into the packaged bootstrap JAR so production launches do not depend on Gradle/dev properties for this check.

## Development baseline

- Minecraft 1.21.1
- NeoForge 21.1.248
- Java 21
- Mod id: `boot_optim`
- Author: Wachipayoxx

The project starts intentionally minimal. Profiling hooks and optimizations are added only when they can be benchmarked and isolated.

# Palladium identifier triage — 2026-09-30

Status: **LIMITED / NO IMPLEMENTATION**. This bounded direct-operation investigation does not add an optimization, profiler, JVM option or game-instance modification. No laptop or game run was performed.

## Authority and scope

Integration refreshed at the start: `agent/integration-current` at `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`. Isolated branch: `codex/palladium-identifiers-20260930`. Mandatory operating guide, README, research/production indexes, model-pipeline deep history and hosted exact-pack contract were read. Current integration contains no Palladium identifier mechanism. Open and closed PR search found no dedicated Palladium title; body matches are incidental lifecycle attribution, not an existing identifier optimization. Root worktrees and the installed instance were left unchanged.

Exact installed mod: `mr_toad_palladium` 1.1.8, author Mr.Toad, SHA-256 `70083d3eaacec032a9ff066474e6df1e81d3e77125fa59f211252dcccec3997e`. Its metadata names `https://github.com/ITsMrToad/PalladiumMod` and LGPLV3. The instance `config/palladium_config.txt` contains `rl_dedup:"all"`, so the inspected identifier path is active.

## Confirmed bytecode behavior

`ModelResourceLocationMixin.cacheInit` splits the variant on commas, maps each component through the existing synchronized property deduplicator, collects a second array, then joins into `variant`. Java `split` discards trailing empty components; blindly keeping the original variant would therefore change behavior for inputs such as `a,,`.

`getVariantByProperties` checks the live deduplication configuration and, when the public property array has positive length, executes `String.join(",", properties)` on every call. `palladium$properties()` returns the actual mutable `String[]`, not a copy. `hashArray` separately hashes that same array. A cached join without mutation validation can return stale text after external array changes. A general snapshot cache adds an array per identifier and must still perform an O(n) comparison; its memory/performance tradeoff is not established.

A read-only scan of every top-level installed mod JAR found `ModelResourceLocationProperties` / `palladium$properties` references only in Palladium's own interface and mixin. This bounds known direct consumers in this pack, but does not prove immutability or cover reflective and future consumers. Enabling the config after constructing identifiers with deduplication disabled also cannot be silently assigned new semantics by an optimization.

## Caller attribution corrects the broad String.join hypothesis

Read existing `jdk.ExecutionSample` events only; no new recording or game run:

| Existing physical JFR | String.join leaf samples | UnionPath filesystem | Palladium constructor | Palladium getter |
| --- | ---: | ---: | ---: | ---: |
| `manual-reload-jfr-20260928/.../rrls-manual-1790622377938.jfr` | 26 | 14 | 6 | 6 |
| `manual-reload-multifocus-20260928/.../rrls-manual-1790624230482.jfr` | 27 | 17 | 7 | 3 |

Paths are below `C:/BootOptimBench/rrls-menu-ready-stage-20260924/`. The second trace contains five sampled stacks including the getter handler, three with `String.join` at the leaf. Most leaf join samples belong to `cpw.mods.niofs.union.UnionPath.toString`, not repeated variant getter reconstruction. These are sampled stack counts across the existing recording, **not exclusive milliseconds, savings, or comparable A/B timing**. The traces do not establish a large Palladium getter budget.

The second trace's exact sampled filesystem caller chains (leaf first) are:

- 16: `String.join -> String.join -> UnionPath.toString -> UnionFileSystem.toRealPath -> UnionFileSystem.findFirstFiltered`.
- 1: `String.join -> String.join -> UnionPath.toString -> Guava Joiner.toString -> Guava Joiner.appendTo`.

Of the first chain, four samples occur on resource workers at request-relative 1.211–1.642 s, eleven on `Worker-ResourceReload-0` at 2.904–8.889 s, and one on Render thread at 11.442 s. The Joiner chain is a resource-worker sample at 0.649 s. The recording exposes five frames for these events; no deeper owner is inferred from this chain. Constructor/build overlap and sample counts still cannot establish an exclusive filesystem saving.

## Decision and reopening

Do not implement the blind variant cache. Do not disable Palladium's deduplication or replace its Guava lifetime/counter behavior on this evidence. A no-cache single-property join shortcut and in-place constructor loop are plausible narrow source changes; neither has a measured critical-path benefit. Their domain is smaller than the original aggregate String.join attribution suggested.

The join executes inside Palladium's merged injection handler, not the vanilla `getVariant` body. A redirect aimed at the vanilla body does not intercept that handler's `String.join`. A BootOptim implementation therefore needs an explicitly validated cross-mixin mechanism or guarded post-Mixin transformation, whereas a direct source change could avoid that plumbing. Introducing this compatibility surface for an unquantified small candidate is not justified in the current small/easy lane.

Reopen if exclusive allocation/CPU or call-domain evidence proves meaningful residual cost, or if an exact maintained source build offers a simple hook. Any candidate must preserve live configuration, mutable array changes (including null components), split/trailing-empty semantics and stock fallback. Reuse the existing recordings before adding instrumentation. Higher-priority work can proceed on multipart classification and resource-path resolution; the latter now owns most observed join leaf samples.

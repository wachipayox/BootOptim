# Exact-pack mod-specific audit addendum — Xaero/Fzzy timing pass

Status: **PROFILED / XAERO DIAGNOSTIC ACTIVE / FZZY HOLD**

This addendum extends `exact-modpack-mod-bottleneck-audit-2026-09-04.md` with a second pass over messages that report their own elapsed time. It was added after the broad audit PR opened because this pass found one stronger mod-specific candidate.

## Xaero World Map — 1.026 s synchronous deferred task

Current hosted exact-pack run `33917611497` reports Xaero's World Map `1.41.0` / XaeroLib `1.1.15` and the following sequence:

- `20:45:57.056` — World Map Stage 1/2 on `Worker-ResourceReload-1`;
- `20:45:59.460` — World Map Stage 2/2 on `Render thread`;
- `20:45:59.467` — region-cache hash message;
- `20:46:00.415` — player-tracker registration and optional-mod checks;
- `20:46:00.486` — NeoForge: `Mod 'xaeroworldmap' took 1.026 s to run a deferred task.`

FancyModLoader's `DeferredWorkQueue` times each owner-tagged `task.run()` individually and executes startup deferred work synchronously. The 1.026 s value is therefore an **inclusive wall measurement of one synchronous startup task**, stronger than an inferred gap between unrelated log messages.

It is still not automatically a 1.026 s TTMM saving. Internal work and dependencies must be attributed before any optimization/defer.

Ownership verification did not find a `wachipayox`-controlled Xaero World Map repository. Direct modification is therefore not proposed.

A separate draft diagnostic PR (#100) samples the existing Render-thread Stage 2/2 interval without changing Xaero semantics:

```text
-Dboot_optim.profileXaeroDeferredTask=true
```

The diagnostic always leaves logging `NEUTRAL`, does not wrap/reorder the deferred runnable, samples at 5 ms, stops on NeoForge's individual Xaero deferred-task warning and removes itself. Its hosted smoke is attribution only; any later cache/defer requires a separate A/B plus first-world/first-map-use gate.

Priority: **higher than speculative config cleanup or warning suppression**, because NeoForge already identifies a synchronous per-mod one-second task.

## Fzzy Config — 304 ms keybind config load

The same exact-pack startup logs report:

```text
Loaded config fzzy_config:keybinds in 304ms
```

on a modloading worker, plus a smaller particle-related config load around 82 ms.

The exact runtime uses Fzzy Config `0.7.6+1.21+neoforge`. The upstream source is third-party (`fzzyhmstrs/fconfig`), not a user-controlled fork in the authenticated repository set.

Decision: **HOLD / no optimization PR**.

Reasons:

- 304 ms is materially smaller than Xaero/CIT/MCEF/resource-model fronts;
- it occurs on a worker, so inclusive wall is not proof of critical-path contribution;
- no repeated parse or title-screen gate has yet been demonstrated;
- caching config state without knowing synchronization/reload semantics would be riskier than the current evidence justifies.

Reopen only if startup attribution shows this config load gates a setup phase or repeats the same decode/validation work, and then preserve stock first parse with exact invalidation.

## Revised priority from the broad audit

1. Attribute Xaero World Map's 1.026 s synchronous Stage 2/2 task via PR #100 exact-pack smoke.
2. Finish CITResewn PR #97 A/B; use its result only to decide whether to escalate into ZIP/open/parse profiling.
3. Recover exact Furnish v29 / MC 1.21.1 source parity before testing a native-NeoForge replacement for the Fabric/Connector artifact.
4. Keep Fzzy Config at HOLD until critical-path or repeated-parse evidence exists.
5. Keep low-ceiling blockstate/path warnings and untimed controlled compatibility mods out of the optimization queue.

A defer is only a success if TTMM improves without transferring the pause to first playable world/map use.

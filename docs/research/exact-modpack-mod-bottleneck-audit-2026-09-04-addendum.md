# Exact-pack mod-specific audit addendum — Xaero/Fzzy timing pass

Status: **PROFILED / XAERO ATTRIBUTION REJECTED / FZZY HOLD**

This addendum extends `exact-modpack-mod-bottleneck-audit-2026-09-04.md` with a second pass over messages that report their own elapsed time.

## Xaero World Map — 1.026 s synchronous deferred task, attribution lane closed

Hosted exact-pack run `33917611497` reports Xaero's World Map `1.41.0` / XaeroLib `1.1.15` and the following sequence:

- `20:45:57.056` — World Map Stage 1/2 on `Worker-ResourceReload-1`;
- `20:45:59.460` — World Map Stage 2/2 on `Render thread`;
- `20:45:59.467` — region-cache hash message;
- `20:46:00.415` — player-tracker registration and optional-mod checks;
- `20:46:00.486` — NeoForge: `Mod 'xaeroworldmap' took 1.026 s to run a deferred task.`

FancyModLoader's `DeferredWorkQueue` times each owner-tagged task individually. The 1.026 s value is therefore an **inclusive wall observation for one synchronous startup task**, not a 1.026 s TTMM-saving claim.

Ownership verification did not find a `wachipayox`-controlled Xaero World Map repository. Direct modification is not proposed.

PR #100 attempted increasingly strict attribution boundaries and is now **closed/rejected as not safely attributable from BootOptim**:

1. Stage 2/2 log -> thresholded slow-task warning: rejected after exact-pack run `33924496490` timed out at 5.003 s and mixed later `glBlitFramebuffer` / `Net.poll` work.
2. normal mixin on `ModLoadingContext.setActiveContainer`: rejected after run `33926828559` reached title with zero BootOptim Mixin errors but ended `title_without_observation` while Xaero still ran Stage 2/2.
3. SERVICE-layer structural transformer on `DeferredWorkQueue`: rejected after run `33927425430` reached title with zero BootOptim Mixin errors but reported `boundary_fields_missing`; the bootstrap FML class was not delivered to BootOptim's transformer.
4. stock `TaskInfo.future` observation: rejected source-level without another smoke. FML 4.0.43 keeps `ParallelDispatchEvent.workQueue`, `DeferredWorkQueue.tasks`, `TaskInfo`, `owner`, `task`, and `future` private. The public future returned to Xaero exists before deferred execution, remains incomplete while queued and while executing, and exposes no exact queued->executing transition. Completion-only observers (`whenComplete`, polling, `join`) cannot recover the missing exact start; private-loader reflection plus another runnable hook would violate the experiment's compatibility/non-invasive gate.

Therefore the original 1.026 s warning remains a one-run inclusive-wall clue only. It does **not** justify a network, Patreon, cache, registry, filesystem, or defer mechanism. Earlier `Patreon.checkPatreon` / `Internet.checkModVersion` mentions came from the contaminated five-second window and remain hypotheses.

Reopen only if FML exposes a supported owner/task lifecycle callback/token or BootOptim gains a supported pre-definition loader hook that can observe the stock owner/runnable boundary without private reflection or task replacement. Another slow-task warning alone is not a reopening premise.

Any future behavior-changing Xaero candidate must gate **TTMM + first singleplayer/multiplayer world readiness + first Xaero map opening/use**. Moving the pause to world entry, first playable frame, or first map use is failure.

## Fzzy Config — 304 ms keybind config load

The same exact-pack startup logs report:

```text
Loaded config fzzy_config:keybinds in 304ms
```

on a modloading worker, plus a smaller particle-related config load around 82 ms.

The exact runtime uses Fzzy Config `0.7.6+1.21+neoforge`. The upstream source is third-party (`fzzyhmstrs/fconfig`), not a user-controlled fork in the authenticated repository set.

Decision: **HOLD / no optimization PR**.

Reasons:

- 304 ms is materially smaller than CIT/MCEF/resource-model fronts;
- it occurs on a worker, so inclusive wall is not proof of critical-path contribution;
- no repeated parse or title-screen gate has yet been demonstrated;
- caching config state without knowing synchronization/reload semantics would be riskier than the current evidence justifies.

Reopen only if startup attribution shows this config load gates a setup phase or repeats the same decode/validation work, and then preserve stock first parse with exact invalidation.

## Revised priority from the broad audit

1. Finish CITResewn PR #97 A/B; use its result only to decide whether to escalate into ZIP/open/parse profiling.
2. Recover exact Furnish v29 / MC 1.21.1 source parity before testing a native-NeoForge replacement for the Fabric/Connector artifact.
3. Keep Fzzy Config at HOLD until critical-path or repeated-parse evidence exists.
4. Keep Xaero closed until a supported exact task boundary appears.
5. Keep low-ceiling blockstate/path warnings and untimed controlled compatibility mods out of the optimization queue.

A defer is only a success if TTMM improves without transferring the pause to first playable world/map use.

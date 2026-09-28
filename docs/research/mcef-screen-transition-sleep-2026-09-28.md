# MCEF screen-transition sleep during first-consumer deferral — 2026-09-28

Status: **production candidate; physical navigation validation pending**.

## Observation and attribution

On the fast PC exact modpack, with BootOptim integration `b3f0c5f`, RRLS screen probe `9253e37` and FancyMenu title-player reuse `f1868878b`, switching Title→Options produced a 1,068 ms displayed-frame gap (1,052 ms before `setScreen`, 15 ms after). Options→Title produced 1,047 ms (1,018 ms before, 28 ms after); other title returns had 1,017–1,022 ms before and 6–22 ms after. Renderer-pair reuse hit twelve times on title returns, so rebuilding those players did not explain the repeated one-second wait.

The full-session watchdog sampled the render thread inside `Thread.sleep` in MCEF's injected `Minecraft.lambda$redirScreen$0` during these transitions. Bytecode of the installed MCEF `2.1.6-1.21.1` JAR confirms the exact path: `CefInitMixin.redirScreen` queues a `Minecraft.execute` task while `MCEF.isInitialized()` is false and download has completed; the task sleeps 1,000 ms, then calls `MCEF.initialize()`. BootOptim's first-consumer mechanism intentionally leaves MCEF uninitialized and suppresses automatic initializer calls. Thus every ordinary eligible menu transition can pay a one-second sleep without progressing CEF. This is real render-thread wall time, not overlapping listener/task-sum time or a startup TTMM comparison.

The initial global resource reload had not completed before this particular user run ended; do not use it as evidence about post-reload navigation. The sleep stack itself and exact MCEF bytecode establish the local cause independently of that boundary. Archived physical logs are under `C:\BootOptimBench\rrls-menu-ready-stage-20260924\combined-title-renderer-reuse-20260928\physical-run` (local operational artifact, not a repository fixture). The broader RRLS/FancyMenu investigation is in `rrls-fancymenu-early-menu-2026-09-24.md` on its separate research branch.

## Candidate and safety boundary

Cancel only a `Minecraft.execute` submission whose immediate stack contains the exact transformed MCEF `redirScreen` handler, while BootOptim's first-consumer state is `DEFERRED` and the exact MCEF version gate passes. The existing `-Dboot_optim.mcefFirstConsumerDefer=false` switch also disables this skip. MCEF's handler still owns download/failure-screen selection; this candidate merely omits its delayed automatic init task after first-consumer deferral is active. Real browser/video consumers continue through the existing real MCEF initializer and normal callbacks. A changed MCEF handler name fails open to stock task submission. This does not move CEF/GL work to a worker, overlap it with resource reload, or claim to fix unrelated EMF/postload frame gaps.

`./gradlew build` passed locally. Hosted exact-pack startup and physical fast-PC navigation/first-consumer validation are required before promotion. A positive result should be judged by disappearing one-second `setScreen` gaps and `status=skipped_redundant_screen_init_task` markers, with no MCEF download/browser regressions. If the exact handler match does not fire or the game requires its automatic initializer for an unguarded consumer, reject or narrow the candidate. The previous rejected MCEF reload-overlap experiment (#78) is a different premise and remains rejected.

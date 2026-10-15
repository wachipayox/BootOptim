# FML event TRACE producer filter — 2026-10-15

## Premise

`ModContainer.acceptEvent` (FML 4.0.4x) logs `Firing/Fired event for modid {} : {}` at
`TRACE` with the `LOADING` marker for every mod and every event. NeoForge's default Log4j
configuration keeps the logger level open and drops `TRACE` only at the appender references,
so with async loggers each call still formats `event.toString()` and publishes a ring-buffer
event that no appender writes (`debug.log` has no such lines). Veil posts one event per shader
setup, so the waste lands on the render thread inside the shader reload.

Earlier evidence: RRLS research (archived `rrls-fancymenu-early-menu-2026-09-24.md`, 2026-09-26)
saw the same stacks; a Veil-scoped RRLS filter (`c4df73e`) denied **0** events. Cause found now:
the filter was attached to the caller's Log4j context, while `ModContainer`'s logger lives in
another context (`AsyncDefault`). PR #262 rejected *message-family* filters for real WARN/INFO
diagnostics; this mechanism only removes events no appender would ever write.

## Measurement before the change (fast PC, JFR)

Origin: fast PC (i5-12600KF), Wachiland Launcher, Beta rev 3, RRLS `b2165ea` + FancyMenu fork
`6c6e888` in `MENU_READY` model-free navigation, RRLS diagnostic `0fc8475` (automatic manual
reload on the title screen), JFR `profile` dumped with `jcmd`. Render-thread samples whose stack
contains `AbstractLogger.trace` under `ModContainer.acceptEvent`: ~19 % of the post-menu reload
tail (gate open → scheduler finished) and **~40 % of the final F3+T freeze**.

## Mechanism

`FmlEventTraceProducerFilter` (default on, `-Dboot_optim.fmlEventTraceProducerFilter=false`
restores stock). In every Log4j context that has the `ModContainer` logger with `TRACE`
enabled, it builds a probe `TRACE`/`LOADING` event and walks the logger-config chain (filters,
appender-ref levels and filters, appender filters, additivity). Only if no appender would accept
it, it adds a configuration-level filter that denies `TRACE` from that one logger. Otherwise:
`[WBOMBO-COMPAT] FALLBACK ... reason=trace_written_by_appender <name>`. A reconfiguration
replaces the configuration and the filter with it (stock). Event dispatch is untouched.

## A/B (fast PC, same session, same JARs, JVM property)

Same origin as above, BootOptim branch build, endpoints: `main_menu` (startup report),
manual reload request → future completion (RRLS log), and RRLS frame watchdog (gaps ≥ 75 ms;
a frame is capped at 20 samples ≈ 3 s, so control final freezes are lower bounds).
Order: primer (candidate, discarded), control, candidate, candidate, control.

| Run | `main_menu` ms | Tail freezes n / sum / max ms | F3+T ms | F3+T freezes n / sum / max ms |
|---|---|---|---|---|
| control 1 | 56 455 | 14 / 2 721 / 1 025 | 13 722 | 7 / 3 739 / 3 043 (capped) |
| control 2 | 60 487 | 13 / 2 922 / 1 162 | 12 885 | 5 / 3 580 / 3 012 (capped) |
| candidate 1 | 57 499 | 11 / 1 540 / 237 | 11 727 | 5 / 2 679 / 2 110 |
| candidate 2 | 63 687 | 10 / 1 320 / 291 | 11 725 | 4 / 2 728 / 2 255 |

Candidates: `ACTIVE`, 0 FALLBACK, ~458 000 events denied before the title in every run.

- Segment (judged first): both candidates beat both controls. Tail freeze sum −49 %, the ~1.1 s
  shader-reload freeze drops to < 0.3 s; F3+T −1.6 s median (12.3 → 11.7 s), final freeze
  ≥ 0.8 s shorter.
- `main_menu`: within noise in this series; no time-to-menu claim.

## Decision

Integrate (segment-first policy). Visual/semantic risk: none for rendering; the only observable
change is that FML event TRACE lines are not produced when nothing would write them.

## Reopening

Reopen if a pack configures an appender that writes `ModContainer` TRACE (the mechanism then
falls back by itself), or if a FALLBACK appears after a NeoForge/FML update.

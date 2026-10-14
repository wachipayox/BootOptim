# JIT compiler pressure on a four-processor JVM — 2026-10-14

Status: **`CICompilerCount=2` VALIDATED on hosted exact-pack (paired), physical gate and delivery pending; C1-only ceiling REJECTED; delayed C2 RETIRED.**

## Premise and why it is not a repeat

The 2026-09-01 laptop JFR (`docs/archive/research/laptop-startup-scaling-2026-09-01.md`) measured 4 m 27 s of aggregate compiler-thread time against ~337 s of startup on a 2C/4T laptop and advised against making JIT tuning a *BootOptim feature* from that profile alone. No JVM compiler flag had ever been A/B tested (searched `TieredStopAtLevel`, `CICompilerCount`, `Tier4*`, `ReservedCodeCache`). This entry tests JVM flags only; nothing ships inside BootOptim. AppCDS (#290) and the JDK 25 AOT cache (#293) are different mechanisms (class data / ahead-of-time code), already rejected.

## Instrumentation (retained)

The startup report now writes a `JVM` line at `mod_entrypoint` and `main_menu`: cumulative process CPU, `CompilationMXBean` total compilation time, GC count/time, code-cache use, heap use and loaded classes. `summarize_startup.py` exposes them as `jvm_<phase>_<metric>` plus post-entrypoint deltas. Active only when the startup report is enabled.

## Measurement

Origin: hosted exact-pack `exact-pack-2026-09-02-v1`, Ubuntu/Xvfb/llvmpipe, Oracle 25.0.4, workflow base JVM args (`-Xmx6G`, user G1 tuning, `-XX:ActiveProcessorCount=4`). Start: process start; endpoint `main_menu` (not `main_menu_presented`). Default `CICompilerCount` at 4 processors is 3 (1 C1 + 2 C2). Zero BootOptim mixin errors and zero `[WBOMBO-COMPAT] FALLBACK` in every run.

Baseline: JIT is 30–40 % of process CPU before the menu (e.g. 103–122 s of compiler time in 275–292 s CPU on slow runners; ~60 s of it before the mod entrypoint).

Independent-VM A/B (3+3) was dominated by two runner speed classes (control `main_menu` 55–91 s) and is used only for the C1 ceiling, whose effect is large. All decisions below use **same-VM paired** runs with alternating order.

| Variant (vs control) | Run | Pair Δ `main_menu` | Pair Δ JIT | Decision |
| --- | --- | --- | --- | --- |
| C1 only `TieredStopAtLevel=1` (ceiling, A/B) | 38574896898 | medians 114.9 s vs 89.3 s; post-entrypoint 86.5 s vs 58.0 s | −93 % | **REJECTED** |
| `CICompilerCount=2` vs stock | 38576409615 | −4.0 / −3.9 / −4.7 s (−7.5 / −4.3 / −5.2 %) | −5.7 / −44.5 / −29.9 s | **VALIDATED (hosted)** |
| Tier4 thresholds ×3 vs stock | 38576402122 | −4.1 / −0.3 / −4.9 s; post-entrypoint −2.6 / +2.4 / −3.2 s | −5.7 / −37.2 / −14.4 s | weaker |
| `CICompilerCount=2` + Tier4 ×3 vs `CICompilerCount=2` | 38577772888 | +1.6 / −2.9 / +0.3 s | −5.2 / −14.7 / −3.9 s | **RETIRED** (no marginal gain) |

`CICompilerCount=2` won all three pairs in both orders (candidate cold-first in pair 2), on fast and slow runners, in both the pre-entrypoint and post-entrypoint buckets, with consistently lower JIT and process CPU and unchanged GC.

### Interpretation

- C1-only removes almost all compilation but the slower C1 code costs far more in the model pipeline than the compiler time it saves. Compilation is not waste; the problem is compiler threads competing with startup workers for four processors.
- Fewer C2 threads trade later peak-code readiness for CPU available to the critical path; on four processors that trade is positive for time-to-menu.

## Remaining gates before the product uses it

1. **Physical laptop** (2C/4T, same `ActiveProcessorCount=4` default count): 2 controls + 2 candidates, menu-only, per the measurement invariants in `docs/agent-guide.md`.
2. **In-game warm-up**: fewer C2 threads delay C2 for gameplay code; check the first minutes in a world for stutter. Steady-state code quality is unchanged (C2 still used).
3. **Delivery**: BootOptim cannot set JIT flags (they are fixed at JVM start). Planned route: per-profile JVM parameters in Distribution, shown as toggles in Pandora/Android launcher (wbombo coordination). Android is a different JVM/hardware and must not inherit this value without its own measurement.

## Reopening criteria

- C1-only: only for a workload without a heavy post-entrypoint model pipeline.
- Delayed C2: only with a different threshold set *and* paired evidence on top of `CICompilerCount=2`.
- Higher processor counts: re-measure; the default thread count and the trade-off change.

PRs: #351 (C1 ceiling), #352 (delayed C2, rounds 1–3), #353 (two compiler threads), counters in the PR adding this entry.

# JIT compiler pressure on a four-processor JVM — 2026-10-14

Status: **`CICompilerCount=2` REJECTED for the product after the physical laptop gate** (hosted win does not transfer: +17.7 s to `main_menu` on the 2C/4T laptop, driven by +40 s before the mod entrypoint). C1-only ceiling REJECTED; delayed C2 RETIRED. The `JVM` startup-report counters are retained as tooling.

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

## Physical laptop gate — 2026-10-14

Origin: physical laptop (Fujitsu Lifebook AH531, 2C/4T, HDD, 7.9 GB RAM), Wachiland Launcher (BootOptimPandora 027c61e) through `tools/laptop-bench/remote_laptop_pandora_run.ps1`, Oracle JDK 25.0.4, `-Xms1024m -Xmx6144m`, user G1 flags, `-Dboot_optim.benchmark.exitOnTitle=true`, BootOptim artifact `93EFECE6…` (this branch). Start: JVM start (startup report uptime); endpoint `main_menu`. One discarded primer, then ABBA. Every run validated the effective Java command line (required/forbidden tokens, per-run marker, Java executable) and was restored with hash checks.

| Run | Order | `mod_entrypoint` | `main_menu` | post-entrypoint | process CPU | JIT | GC |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| control c1 | 1 | 156.9 s | 506.4 s | 349.5 s | 1263 s | 357 s | 29.7 s |
| candidate b1 | 2 | 189.1 s | 491.0 s | 301.9 s | 1115 s | 279 s | 18.5 s |
| candidate b2 | 3 | 189.2 s | 460.8 s | 271.5 s | 1044 s | 278 s | 15.2 s |
| control c2 | 4 | 141.6 s | 410.1 s | 268.5 s | 1196 s | 362 s | 15.6 s |

ABBA means: `main_menu` control 458.2 s vs candidate 475.9 s (**+17.7 s, +3.9 %**); before the entrypoint 149.3 s vs 189.2 s (**+39.9 s**, both candidates above both controls even though the controls bracket them in time); after the entrypoint 309.0 s vs 286.7 s (−22.3 s, inside a strong run-order drift: controls 349.5 → 268.5 s). JIT −22 %, process CPU −150 s.

Interpretation: on four slow logical processors, `CICompilerCount=2` keeps one C1 thread and halves the C2 threads. Before the entrypoint (FML discovery, Mixin/ASM transformation, class loading, mostly serial) the hot ASM/Mixin code waits longer for C2 and the serial path slows by ~40 s; the CPU it frees only helps the parallel model phase afterwards. On hosted runners with faster cores the C2 queue drains quickly enough that the pre-entrypoint cost does not appear (−1.9 to −3.7 s there). The segment the flag was meant to help does not offset the regression on the target hardware.

Decision: **do not ship `CICompilerCount=2`** as a profile JVM parameter. No BootOptim code depended on it.

Laptop context: these 410–506 s menus are slower than the September 2026 physical baseline (~350 s, Prism, different pack/state); compare only within this campaign.

## Reopening criteria

- C1-only: only for a workload without a heavy post-entrypoint model pipeline.
- Delayed C2: only with a different threshold set *and* paired evidence on top of `CICompilerCount=2`.
- Higher processor counts: re-measure; the default thread count and the trade-off change.
- `CICompilerCount=2`: only with a mechanism that protects the pre-entrypoint serial path (for example a flag change applied after the mod entrypoint, which HotSpot does not support today) or on hardware where the physical gate passes.
- The laptop result suggests pre-entrypoint time is sensitive to C2 throughput; a *higher* C2 count is an untested, separate premise and needs its own physical ABBA.

PRs: #351 (C1 ceiling), #352 (delayed C2, rounds 1–3), #353 (two compiler threads), #354 (counters, this entry, laptop runbook).

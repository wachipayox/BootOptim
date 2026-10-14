# Oracle JDK 25 AOT cache on the physical laptop — 2026-09-25

## Decision

**NO-GO for the current private launcher profile.** The AOT cache was generated,
loaded, and used by HotSpot, but one physical control/cache pair showed only a
3.043 s difference (0.81%) at the BootOptim `main_menu` endpoint. The cached
run was last in the sequence, so warmer filesystem state could explain that
small delta. This is not evidence of a repeatable startup win, and the cache
does not justify changing the launcher's configured Java runtime.

The active Pandora profile was configured for Oracle Java 21.0.9. AOT caches
require JDK 24 or newer, so the experiment temporarily selected the already
installed Oracle JDK 25.0.4 and restored the profile to Java 21 after testing.
Unless the private launcher adopts JDK 25, this mechanism is unavailable to its
normal launch. Oracle documents AOT cache training, assembly, production,
compatibility keys, and `-Xlog:aot` in the [JDK 25 `java` command reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html).

## Question and method

Does a locally trained JDK 25 AOT cache make the full private pack reach its
main menu sooner on the old laptop?

The same `BootOptimLaptop` instance and pack were launched through Pandora's
Normal CLI route in the same interactive session. Control, training, and cache
consumption all used Oracle JDK `25.0.4+7-LTS-189`, the same 6 GiB maximum heap,
and the same BootOptim startup marker. The measured interval is JVM startup
(`started=` in `bootoptim-startup.log`) to `PHASE name=main_menu`; it excludes
Pandora preflight and task dispatch. Minecraft was closed normally after the
menu marker. Order was control → training → cache consumption, without reboot.

The training run used `-XX:AOTConfiguration` and `-XX:AOTCacheOutput`. The
production run used `-XX:AOTMode=on`, `-XX:AOTCache`, and `-Xlog:aot`.
HotSpot logged both `Opened AOT cache` and `Using AOT-linked classes: true`,
so this is a valid cache-consumption test rather than an inactive-option run.

## Measurements

| Run | BootOptim startup → main menu | Notes |
|---|---:|---|
| Control | 375.124 s | No application AOT cache |
| Training | 395.315 s | Cache-recording run; not used as a performance candidate |
| Cached | 372.081 s | AOT cache loaded and AOT-linked classes enabled |

Cached minus control was **−3.043 s (−0.81%)**, with one run per condition.
The cached run's `mod_entrypoint` marker was 126.886 s versus 110.896 s for
control (+15.990 s); later work erased that gap by the menu endpoint. Given the
fixed order, the small end-to-end difference can be page-cache or ordinary
run-to-run variance. It cannot be claimed as an AOT benefit.

The generated `game.aot` was **331,218,944 bytes (316 MiB)**. The separate
training/assembly work took an additional full launch and produced a
337,248,256-byte configuration file. The test cache and config were removed
afterward; per-run startup reports, logs, AOT-use log, autoclose results, and
summary remain at `C:\BootOptimBench\results\bootoptim-aot-laptop-20260925`.

## Risks and limits

- This was a quick feasibility pair, not a balanced multi-pair benchmark. No
  reliable performance claim can be made from the small delta.
- The cache is tied to application/classpath, JDK release, OS, and CPU
  architecture. A pack update or Java update can require regeneration.
- The measurement is Java startup to BootOptim's menu marker, not complete
  launcher Start-to-menu time. It does not include cache generation or updater
  work.
- The current launcher profile uses Java 21.0.9, whereas JDK 25 was selected
  only temporarily for the test. Returning to Java 21 was verified after the
  experiment.

## Reopening criteria

Revisit only if the launcher standardizes on JDK 25+ and a new hypothesis
identifies a narrower, critical-path set of classes worth caching. Require
HotSpot confirmation that the cache is used and balanced physical A/B runs
showing a material Start-to-menu improvement after cache/update overhead. The
previous full-archive AppCDS no-go and its evidence are recorded in
[BootOptim PR #290](https://github.com/wachipayox/BootOptim/pull/290); this AOT
trial does not reverse that decision.

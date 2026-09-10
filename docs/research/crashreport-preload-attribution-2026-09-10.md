# CrashReport.preload internal attribution — 2026-09-10

Status: **ACTIVE DIAGNOSTIC / PROFILE-ONLY / NO SAVINGS CLAIM**

## Scope and prior evidence

Agent 100 continues the valid hosted exact-pack attribution from PR #231. That single hosted run measured 859.889 ms wall across the synchronous `CrashReport.preload()` call in `Main.main`, before `BackgroundWaiter.runAndTick`. The value is an observed callsite wall, not an optimization estimate.

Authority remains `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`. The diagnostic branch is layered on the #231 diagnostic head only to retain its already-proven single ModLauncher fork, inherited diagnostic Mixin fork, strict two-request Bootstrap parser, and outer `CrashReport.preload` boundaries. None of those diagnostics is production code.

The assignment-referenced `docs/research/boot-pipeline-program-2026-09-08.md` is absent from the pinned integration tree; PR #200 independently recorded that absence rather than inventing its contents.

## Stock 1.21.1 topology and diagnostic boundary

Mapped 1.21.1 source has the intentional warmup shape:

```text
CrashReport.preload()
  -> MemoryReserve.allocate()
  -> new CrashReport("Don't panic!", new Throwable())
  -> getFriendlyReport(ReportType.CRASH)
       -> getFriendlyReport(ReportType, List)
            -> getExceptionMessage()
            -> getDetails(StringBuilder)
```

The warmup therefore combines memory-reserve readiness, dummy object/system-report construction, Throwable stack capture, stack rendering, detailed/system-report rendering, and small formatting/delegation residuals. Moving, skipping, deferring, or parallelizing any of this would change the early-crash path and is outside this diagnostic.

## Instrumentation design

`CrashReportPreloadProfileTransformer` targets only `net.minecraft.CrashReport`. It fails closed unless all of the following 1.21.1 topology is unique and ordered before any bytecode mutation:

- `preload()V` and the core `getFriendlyReport(ReportType,List)` overload;
- one `MemoryReserve.allocate()V`;
- one `NEW CrashReport` followed by one exact `CrashReport(String,Throwable)` constructor call;
- one `ReportType.CRASH` field read and one outer `getFriendlyReport(ReportType)` call;
- one `getExceptionMessage()` followed by one `getDetails(StringBuilder)` in the core renderer;
- one normal `RETURN` in `preload`, no preload branch/try-catch topology, and one `ARETURN` in the core renderer.

Markers are JDK-only, property-gated by `-Dboot_optim.crashReportPreloadTrace=true`, use `System.nanoTime`, and swallow their own diagnostic failures. They do not catch stock work or change stock exceptions.

No marker is inserted between a `NEW` instruction and its constructor while an uninitialized object can be live on the operand stack. Consequently the `dummy_report_construction` scope intentionally includes nested `Throwable` construction/stack capture and `CrashReport` field initialization (including `SystemReport`). This is safer than attempting a finer constructor split with verifier-sensitive hooks.

The parser tiles the inherited #231 callsite wall into:

```text
outer before CrashReport.preload
  -> class-init / entry-dispatch residual
  -> preload entry residual
  -> MemoryReserve.allocate
  -> post-reserve residual
  -> dummy report construction (Throwable + SystemReport included)
  -> post-construction residual
  -> friendly report total
       -> overload entry residual
       -> header/time/description residual
       -> getExceptionMessage stack-trace formatting
       -> inter-render residual
       -> getDetails + system-report rendering
       -> core return residual
       -> overload return residual
  -> post-render residual
  -> preload exit / outer-return residual
outer after CrashReport.preload
```

Nested renderer scopes are never added to the outer call as independent savings. The parser requires the same thread/order, exactly one copy of every marker, and exactly two Bootstrap requests with the #229/#231 provenance and shared loader/TCCL contract.

## Runtime validity gates

The dedicated profile is a single hosted exact-pack run only. It must pass all of the following or the measurement is invalid:

- build/packaging and normal startup gates;
- exactly one diagnostic ModLauncher GAV/source and one inherited diagnostic Mixin GAV/source, stock SecureJarHandler;
- exactly two Bootstrap requests in the expected Mixin-then-classloading order and same loader/TCCL identity;
- main menu reached;
- exact resource selection 14/14 in identical order and reload count 1;
- exactly one internally tiled `CrashReport.preload` sequence.

No laptop run and no A/B are requested.

## Hosted result

Pending the Agent 100 hosted profile. Final measurements and disposition will be recorded after the run; until then no production intervention is authorized.

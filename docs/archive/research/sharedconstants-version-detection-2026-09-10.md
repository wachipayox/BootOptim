# SharedConstants version-detection attribution — 2026-09-10

Status: **PROFILED / NO VERSION-DETECTION OPTIMIZATION YET**

PR: #235

Authority at branch creation: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Target and premise

PR #231 measured one hosted exact-pack run with 440.448 ms wall around the `SharedConstants.tryDetectVersion()` callsite in `Main.main`, before `BackgroundWaiter`. That outer number included first-use class initialization, so it was not a valid savings ceiling for resource I/O or JSON parsing.

Agent 101 adds diagnostic-only, version-pinned, fail-closed boundaries around the exact Minecraft 1.21.1 topology in `SharedConstants`, `DetectedVersion`, and `GsonHelper`. The probe does not cache or substitute the version, wrap the stream, change exceptions, force class initialization, move work to another thread, or alter publication of `SharedConstants.CURRENT_VERSION`.

The parser deliberately treats `GsonHelper.parse(Reader)` as **JSON parse including stream reads**. Gson consumes the `InputStreamReader` inside that call, so stock bytecode does not expose a trustworthy pure CPU-parse versus later JAR-read split without wrapping/replacing the reader, which is outside this observational diagnostic.

## Measurement contract

Origin: hosted exact-pack Linux/Xvfb/llvmpipe surrogate.

Endpoint: `main_menu`.

This is a single-run monotonic wall attribution, not an A/B result and not a savings estimate. The inherited #231 harness must still prove exactly two Bootstrap transformation requests with the pinned ModLauncher/Mixin replacements, and resource selection/reload validity remains a hard gate.

The first runtime-valid Agent 101 launch was workflow run `34482643140`. It reached the menu and passed the resource-validation launch step. Its first parser revision then failed because it incorrectly required every `SharedConstants.tryDetectVersion()` marker to be globally unique. The raw trace proved three legitimate calls during startup: one in the bounded `Main.main` call, then later calls on a ForkJoin worker and the Render thread after `CURRENT_VERSION` had already been published. The corrected parser therefore remains fail-closed on exactly one complete nested marker chain **inside the pre-existing Main before/after callsite interval**, while merely counting later calls outside that target interval.

## Raw attribution from run 34482643140

The bounded `Main.main` call measured **411.182498 ms** wall in this run.

- `SharedConstants.<clinit>`: **399.680179 ms** inclusive wall inside the outer call.
- outer call entry to `SharedConstants.tryDetectVersion` method entry: **407.075577 ms**; this includes SharedConstants linkage/class initialization and therefore is intentionally broader than `<clinit>` itself.
- `DetectedVersion.<clinit>`: **0.797504 ms** inclusive wall.
- `Class.getResourceAsStream("/version.json")`: **0.130014 ms** for resource lookup/open only.
- `GsonHelper.parse(Reader)`: **0.232696 ms**, including stream reads caused by Gson consuming the reader.
- parse return through stock `DetectedVersion(JsonObject)` construction: **1.243368 ms** residual.
- original `PUTSTATIC SharedConstants.CURRENT_VERSION` publication boundary: **0.049022 ms**.
- `GsonHelper.<clinit>`: **0.150352 ms**; it occurred inside `SharedConstants.<clinit>`, not at the later version-parse boundary.

The measured subsegments tile the full outer call exactly; class-initialization scopes are also reported independently and are inclusive, so they must not be added on top of the tiled segments.

## Interpretation

The material wall attributed by #231 is not resource/JAR I/O, JSON parsing, version-object construction, or version publication. In the raw valid run, about 400 ms sits in first-use `SharedConstants` class initialization. The actual version-resource/parse/publication work is only a few milliseconds in aggregate on this hosted run.

`SharedConstants.<clinit>` is not a demonstrated pure boundary. Its exact 1.21.1 shape performs global class-initialization work including Netty resource-leak configuration and Brigadier exception-provider setup, with transitive class loading/initialization inside the same JVM class-init semantics. Moving, pre-running, caching, or splitting that work would change initialization order and potentially observable global state unless a separate diagnostic proves a narrower pure subphase.

The later `SharedConstants.tryDetectVersion()` calls are also not a useful optimization target: after the first publication they take the existing stock fast path and do not repeat the expensive detection chain.

## Decision

**No production optimization is justified from the version-detection path.** In particular, do not cache `version.json`, replace `DetectedVersion`, preparse JSON, publish a substituted version, or pre-initialize `SharedConstants` elsewhere based on this profile.

A future experiment is justified only under a materially different premise:

1. a separate version-pinned diagnostic splits the material `SharedConstants.<clinit>` wall and identifies a large subphase that is demonstrably pure/order-independent despite class-init semantics; or
2. a comparable profile shows resource lookup/stream consumption/JSON parse becoming materially expensive outside first-use class initialization.

Until then, the safe conclusion is to keep stock version detection and treat `SharedConstants.<clinit>` as a separate diagnostic candidate rather than a version-I/O optimization frontier.

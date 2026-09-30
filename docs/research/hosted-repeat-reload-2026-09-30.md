# Hosted same-process resource reload diagnostic

Status: **ACTIVE DIAGNOSTIC — not production**

Base: refreshed `agent/integration-current` at
`a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.

The hosted fixture previously stopped at the first title. Startup A/B can
reject bad loading candidates but cannot measure a repeated reload's cache
lifetime or compare two later generations in the same JVM. This diagnostic
adds `-Dboot_optim.benchmark.repeatReloads=2` (valid range 1–3), effective only
when the existing `boot_optim.benchmark.exitOnTitle=true` is also present.
Normal interactive sessions remain unaffected.

The first main-menu marker/report remains exactly the original startup
endpoint. Instead of exiting immediately, the benchmark waits for the stock
overlay to disappear and two ready seconds on the title, invokes the
stock `Minecraft.reloadResourcePacks()` and measures until its returned
future completes. It never blocks the render frame, changes resources or
listeners, modifies callbacks, moves GL work or publishes a partial model
generation. A worker completion is handed back through a volatile immutable
record; follow-up requests occur in the render-frame post event, on the
render thread even with Ixeris installed. Between reloads it
waits for the normal overlay/fade and another two ready seconds. Any failed
future stops the diagnostic. An unexpected world also aborts it.

There are only begin/end markers per repeat, plus arm/done. Monotonic
`System.nanoTime` request-to-future wall is distinct from startup time and
from inclusive listener task sums. This is a **menu resource reload on
Linux/llvmpipe**, not physical in-world F3+T timing, gameplay freeze time,
visual equivalence or a laptop result. It is useful for rejecting cache and
preparation premises before another manual run.

The existing resource-selection checker validates every effective pack list.
The summarizer adds a separate `repeat_reloads` result and rejects missing,
duplicated, failed or unordered markers, changed measurement boundaries and
an effective reload count other than initial plus requested repeats. The
panorama startup field now selects the first preload, so later reload work
does not silently overwrite a startup metric. Seven parser tests cover
successful duration extraction, ordinary startup, failure, duplicate,
truncation, extra reload and invalid origin; local packaging passes. Hosted
runtime validation is pending.

## First hosted failure and correction

[Run 36687765308](https://github.com/wachipayox/BootOptim/actions/runs/36687765308)
reached the existing main-menu marker at 86.924 seconds and armed the harness,
but invoked **zero repeat reloads**. The timeout dump shows a live render
thread drawing `com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen`,
not a model-preparation deadlock. That screen replaces the title because
the fixture lacks optional Lavaplayer binaries. This result is invalid for
repeat-reload performance; its logs/dump are retained at
`C:/BootOptimBench/hosted-repeat-reload-20260930/failed-v1/`.

Exact installed AnalogAudio 0.1.0 bytecode proves that the welcome screen's
`onClose()` is identical to its decline button: return to `lastScreen`, with
no download or config write. The opt-in CI harness now calls that method
once for this exact screen class after the overlay disappears. Unknown
screens are never dismissed. Readiness uses monotonic seconds after rendered
frames instead of tick count, and a 180-second per-phase limit logs the
screen/overlay class and fails rather than silently waiting for the full
process timeout. No interactive setting or fixture resource selection is
changed. FerriteCore's concurrent smoke was cancelled because it used the
same invalid harness; it provided no candidate-performance evidence.

## Next candidate premise

Physical F3+T JFR's bake interval contains FerriteCore 7.0.3 quad
deduplication and rehash samples. Exact installed bytecode and upstream
1.21.1 source show `Deduplicator$1.apply` clears then trims
`BAKED_QUAD_CACHE` after model loading. The next reload grows the same table
again. A bounded retained **empty table capacity** might avoid rehash work
while preserving array canonicalization and generation clearing; no code
for that candidate is included here and no savings ceiling is established.
The existing stronger per-int Murmur hash must stay: upstream documents
that plain `Arrays.hashCode` caused collision slowdown (#129).

Source: [FerriteCore 1.21.1 Deduplicator](https://github.com/malte0811/FerriteCore/blob/1.21.1/Common/src/main/java/malte0811/ferritecore/impl/Deduplicator.java).
Before implementing a capacity candidate, prove the storage bound, unchanged
canonical array choices, exact original clear timing and fail-open version
guard, then exercise repeated generations in this harness.

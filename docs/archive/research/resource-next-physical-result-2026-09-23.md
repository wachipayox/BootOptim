# Physical resource constructor and first-screen result — 2026-09-23

Status: **PROFILED, NO OPTIMIZATION PROMOTED**. Diagnostic source and safety
contract: [resource-next-diagnostic-2026-09-23.md](resource-next-diagnostic-2026-09-23.md).

## Validity and measurement boundaries

Run `resource-next-20260923c` used Prism's BootOptimBench instance on the old
HDD laptop, Oracle Java 21, 6144 MiB heap, 14 selected packs, original JVM
tuning and the packaged diagnostic wrapper SHA-256
`53b774844e4dccb8ce28a6d1f4acac52e63646b78b2fd4bde509c21c9432d5dd`.
The remote transaction captured Java PID 3956, creation
`2026-09-23T10:48:21.2035450+02:00`, expected Java path and all five required
arguments. Run state finished valid. The initial-reload origin is this JVM's
first `resource_reload` start marker; endpoint is that reload's successful
completion, both System.nanoTime. First-screen endpoint is the first
`Window.updateDisplay` return after TitleScreen opening. JVM uptime there was
**363.787 s**; the first BootOptim bootstrap marker appeared at uptime
34.516 s. This excludes launcher preparation and is not launcher-to-menu time.

The early buffer persisted 8 rows, dropped 0. Offline `resource_next` parsing
accepted the combined logs with 24 balanced scopes, no warnings, one initial
reload and 70/70 successful listeners with one barrier call each. Clock-origin
offset ranged -1912 to -1906 ms, within the unchanged 5 s validity tolerance.
Selected pack names and priority order matched the preceding physical
reference, with no resource-pack fallback or BootOptim/Mixin error. The first
presented screen was `TitleScreen`; automated exit does not prove usable input.
EMF model-creation-limit errors also existed in earlier pack logs.

Evidence lives at `C:/BootOptimBench/results/resource-next-20260923c/`:
latest/startup/early logs, finished/restored transaction states, options,
variance.json, resource-selection.json and attribution.json. Postflight
restored the original config SHA-256
`a9744bf7a4c5660f6b58a6fe1fe9309a91ecacc2ac2189bf099dd2a145aa1ecc`
and wrapper SHA-256
`379bc509efd43a0d2edf7cfb6bc1e2f99dd1601b3d8e3ab43b00c70f6dea4989`.
Integration was refreshed after collection and remained `b3f0c5f`.

## Constructor ownership and preparation gate

Initial reload: **177.440 s** monotonic wall. ModelManager preparation ended
at reload-relative **131.684 s**, just **25.231 ms** before the global
preparation barrier. Its listener completed around 139.694 s, leaving
**37.746 s** of ordered reload tail. Listener preparation/task durations
overlap and are not summed to get this result.

| Disjoint ModelBakery constructor interval | Wall s | Owner-thread CPU s |
|---|---:|---:|
| `ActiveCITs.load` | 24.231 | 8.875 |
| Stock blockstate registration | 22.880 | 12.953 |
| Blockstates return → first `popPush("items")` return | 14.653 | 9.031 |
| Vanilla item loop | 0.846 | 0.406 |
| NeoForge additional-model event | 1.305 | 0.703 |
| Additional-model loop | 0.024 | 0.016 |
| Parent resolution | 1.762 | 1.188 |
| Uninstrumented remainder | **0.173** | **0.063** |

The outer constructor was **65.875 s** wall / **33.234 s** owner-thread CPU.
All seven children were contained within it and mutually disjoint by
System.nanoTime, so the 0.173 s remainder is valid interval subtraction.
The 14.653 s interval contains stock `getModelGroups` and the optional
CITResewn item-model injection documented in PR #221; this probe does **not**
time that injected method exclusively. Hosted PR #221 measured stock
`getModelGroups` at 0.005 ms, making CIT item loading the leading source-level
hypothesis here, not a confirmed 14.653 s owner. The existing CIT base-item
model cache was already active in the pinned pack (PRs #151/#257). These
numbers do not reopen a simple cache or scheduling change across CIT's mutable
reload-generation lifecycle.

Other overlapping work: block-model loading 25.256 s, atlas loading 43.360 s,
model baking 33.621 s inside model loading 39.862 s. Atlas completion at JVM
uptime 222.533 s preceded constructor completion at 270.300 s. These
preparation durations must not be added.

## Ordered reload tail and first screen

EntityRenderDispatcher's ordered listener interval was **17.827 s**. Its
disjoint entity-provider creation was 14.528 s, player-provider creation
3.184 s and AddLayers 0.100 s, leaving about 0.015 s outside these calls.
The player-provider call used only 0.156 s owner-thread CPU, so its 3.184 s
wall contains unresolved waiting/stall time. It does not prove disk, GPU or a
particular provider. Other substantial listener turns: historical lambda
slot 25 8.485 s, GameRenderer 3.874 s, ClientModLoader lambda 2.576 s,
ModelManager apply 1.982 s and Veil ShaderManager 1.951 s. Generated lambda
addresses are not durable identities; use the prior PR #216/#184 source map
before targeting them.

TitleScreen opened at JVM uptime 358.382 s; first display returned at
363.787 s, **5.405 s** later. **5.239 s** elapsed before
`GameRenderer.render` returned after opening. First-frame blit took 0.011 s,
and `Window.updateDisplay` took 0.150 s; small gaps account for the rest.
The full render scope is intentionally absent because TitleScreen opened
after renderer entry. Cumulative whole-JVM CPU rose 9.234 s and GC time
0.209 s from opening to render return. Logs in that interval show FancyMenu
initial-layout activity, Iris pipeline creation and EMF limit messages.
They show activity, **not** exclusive ownership of 5.239 s. The display call
itself is not the major interval in this run. Available OS memory was about
630 MiB at opening and 626 MiB at presentation; this suggests pressure worth
checking, not measured hard faults or page-cache behavior.

## Decision and reopening criteria

The previous physical run's reload lasted 142.333 s and title-to-display
19.104 s. Its clock validation failed, and cache/physical conditions were
uncontrolled. The difference is not a candidate speedup. This run is
attribution only; no runtime optimization was promoted.

The constructor has no remaining large *unmeasured* interval. A future
optimization premise needs source-level work in the 24.231 s ActiveCITs path,
the 14.653 s post-blockstate/CIT item path or the 22.880 s blockstate path,
with reload-generation and ordered-callback equivalence. PR #257's CIT
lifecycle NO-GO and the retained indexed blockstate matcher constrain that
work. The 5.239 s first-render tail needs a bounded subphase or sampling proof
before assigning it to FancyMenu, Iris, EMF, native work or memory pressure.
Changed-pack generation 2+ reloads, first-world usability and semantic/visual
equivalence remain unmeasured.

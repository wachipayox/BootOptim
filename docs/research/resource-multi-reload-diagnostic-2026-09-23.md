# Manual resource-pack reload diagnostic — 2026-09-23

Status: **prepared; laptop run requires the user's explicit signal**. This is
diagnostic-only work on PR #282, based on current integration `b3f0c5f`. It
extends the existing resource profiler to generations after the first title
frame; it is not a proposed startup optimization.

The valid previous physical run `resource-next-20260923c` established a
177.440 s initial reload, 131.684 s ModelManager preparation gate and 70/70
listener lifecycles. It did not measure an Options-screen reload. First-load
optimizations in PRs #214/#216 deliberately scope their caches to generation
one, so generation-two behavior cannot be inferred from those results.

## Question and method

Measure the initial reload and two subsequent manual reloads in one JVM: one
with an enabled external ZIP pack removed, then one with that same pack
restored. In Minecraft 1.21.1, `Options.updateResourcePacks` requests a
reload only when the selected pack list differs from the list when the
Resource Packs screen opened. Toggling away and back *before* pressing Done
would not exercise a reload. The user therefore needs two separate Done
commits, waiting for each loading screen to finish before the next action.

The probe records the stock reload's start, all-preparations barrier, allDone,
listener preparation/turn/completion rows and JVM CPU/GC/memory snapshots by
generation. It also records the public `Minecraft.reloadResourcePacks()`
request/future interval, `LoadingOverlay` removal call, and the first
`Window.updateDisplay` completion after that removal. Listener rows are
buffered until a completed frame. The listener intervals are inclusive and
overlap; they must not be summed. The manual request future may complete
before the loading overlay's fade-out, so these are distinct endpoints. None
of the probes changes reload scheduling or calls render-thread work elsewhere.

The offline `multi_reload` profile requires an unambiguous early JVM marker,
first-menu endpoint, three sequential successful generations, a matching
manual request for generations two and three, one post-overlay frame per
generation, balanced scopes, and complete successful listener rows. The
selection checker requires baseline and final `resourcePacks` and
`incompatibleResourcePacks` options to match exactly, the effective external
ZIP list to change in an intermediate `Reloading ResourceManager` log line,
and the final effective list to return to baseline. A failed gate means
inconclusive evidence, not a performance result. If another mod initiates a
fourth reload, record it as an extra generation and inspect its cause.

The run preserves the exact existing laptop JVM tuning and startup profile,
enables `profileStartupVariance`, and **removes**
`benchmark.exitOnTitle=true` so the user can interact with the menu. The
transaction verifies the actual Java command line and restores the Prism
configuration and original BootOptim wrapper after normal game exit. Keep
launcher preparation outside the timing boundary. No claim about visual or
in-world equivalence follows from the selection check alone.

## Validation and remaining gate

Local `./gradlew.bat build --offline` and 47 Python tests pass. The next
runtime gate is a hosted exact-pack smoke for the new Mixin targeting before
the interactive laptop launch. The laptop result must record the physical
hardware, pack state, actual JVM start marker and menu/reload endpoints. It
should compare generation-specific critical-path wall and CPU within the
same JVM; a single run is attribution evidence, not an A/B speedup.

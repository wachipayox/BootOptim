# One-run deep resource reload diagnostic — 2026-09-23

Status: **FINAL HOSTED FIRST-GENERATION GATE PASSED; physical validation pending**. This extends
diagnostic PR #282 from integration `b3f0c5f` after the valid three-generation
physical result. The previous run proved that ModelManager's preparation is
the barrier and that `block_models` grew 21.906 → 81.103 → 133.518 s despite
changing only a font ZIP. It also left 36–50 s from allDone to LoadingOverlay
removal unattributed. No optimization is promoted by this probe.

## Instrumentation contract

All Java probes remain opt-in under `profileStartupVariance=true`. The pinned
1.21.1 `ModelManager` synthetic lambda callsites are checked against the
mapped bytecode. The new scopes separately bound model and blockstate
resource listing, async task enqueue, and final map collection. Existing
`block_models` / `block_states` futures still give the total wall endpoints.
The listing result records key counts, stack-entry count and an order-aware
or commutative **noncryptographic source fingerprint**. A matching fingerprint
is only evidence that IDs and source-pack precedence match; it does not prove
identical resource bytes.

The exact `Resource.openAsReader`, `BlockModel.fromStream` and blockstate
`GsonHelper.parse` callsites are wrapped once and call the original operation
exactly once. Per-file observations update bounded in-memory accumulators by
generation, domain and source pack. There is no per-file log line, resource
reopen, content read, executor wrapper, replacement future or output cache.
After a completed displayed frame, one generation summary and pack rows
report counts, failures, open/parse task sums and maxima. These sums are
overlapping worker time, **not** critical-path savings or exclusive CPU. The
JFR profile adds thread samples, GC/allocations and slow file-read evidence;
its overhead makes this an attribution run, not an A/B benchmark.

The post-menu `LoadingOverlay` probe records first observed reload completion,
fade start, frame count and accumulated/max durations for inter-overlay-render
gaps, `LoadingOverlay.render`, `GameRenderer.render`, blit and
`Window.updateDisplay`. It buffers one summary until the first display after
overlay removal. No per-frame logging or extra render/GL calls occur. A large
gap indicates unmeasured time between render invocations, not automatically
GPU, disk, user interaction or OS descheduling; JFR samples and logs must be
correlated before assigning cause.

## Single physical gate

The requested physical run repeats the same two Done commits: remove only
`Ashen_Custom_Font (1).zip`, wait for the Resource Packs loading screen to
finish, restore the same ZIP in its original priority, wait again, then quit
normally. Preserve the original Oracle Java 21/G1/6 GiB settings; add
`-XX:StartFlightRecording=name=BootOptimDeep,settings=profile,filename=...,dumponexit=true,maxsize=256m`.
The profile setting was syntax-tested locally on Java 21. The transaction must
verify the effective Java/JVM arguments, original config/wrapper hashes,
non-stale early trace/JFR destination, and then restore the original instance.

The offline `multi_reload_deep` gate requires the earlier three successful
generations, exact pack-state restoration, six new scope pairs and a complete
model summary for each generation. The model/state task counts must equal
discovered key counts; pack rows and open/parse counters must be present.
At least two completed post-menu overlay summaries must include observed done,
fade, render and display data. JFR must exist, be readable, and include GC,
execution/allocation samples and file-read events or explicitly report which
event family was absent. Preserve the raw recording and source logs.

## Decision use and limits

If listing dominates `block_models`, investigate repeated pack enumeration
and the already-researched ZIP-index ceiling (#141/#142) before changing a
cache. If open dominates parse task-sum and JFR has slow file-read/ZipFS
stacks, focus on resource-open/materialization and page-cache effects. If
parsing dominates, evaluate current-generation immutable parse reuse with
exact winning-resource and invalidation rules. If enqueue/join or GC dominates,
examine task fan-out/retained allocations before touching data caching. The
overlay summary distinguishes a long callback/fade interval from frames
blocked in render or display; it does not grant permission to move GL work.
The run should make the *first* architecture choice concrete, but no single
profile can guarantee that a later behavior-changing candidate passes hosted
A/B, visual and physical regression gates.

## Hosted first-generation gate

[Exact-pack smoke #35855584589](https://github.com/wachipayox/BootOptim/actions/runs/35855584589)
on diagnostic commit `45d431b` reached the main menu in 72.778 s with zero
BootOptim/Mixin errors. The full console, including the bootstrap prefix,
passed `deep_smoke` with no invalid reasons or warnings. All six new scopes
fired, and the completed ModelManager generation reported 44,708 model keys
and tasks, 11,484 blockstate keys and tasks, 11,603 blockstate resource
entries, and 95 pack rows. One model parse failed under the stock catch path;
the probe counted it rather than hiding it. This confirms the mapped synthetic
lambda targets and exact resource-open/parse wrappers execute on the pinned
pack. It is not a manual-reload or physical-HDD result. After this smoke, the
six end markers were corrected to retain their generation subject, and the
offline gate now requires both start and end in each generation; the correction
passed its own [hosted exact-pack smoke #35863667309](https://github.com/wachipayox/BootOptim/actions/runs/35863667309)
on commit `5970592`. That run reached the menu with zero BootOptim/Mixin
errors; `deep_smoke` was valid with no warnings, 44,708/44,708 model
keys/tasks, 11,484/11,484 blockstate keys/tasks, 95 pack rows, and all six
paired scopes attributed to `deep_1`. Its 89.815 s menu result is not an A/B
comparison to the previous 72.778 s smoke: fresh hosted VM and diagnostic
overhead differ. The Windows distributable from `bootstrap/build/libs/` has
SHA-256 `9E0EF52F31281A9672077F712BAF1B66DD236C92D421242E406CC3A96C2FCADD`.
The physical launch and collection scripts are prepared locally, but no
connection or staging on the laptop has been made for this diagnostic.

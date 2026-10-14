# Physical manual resource-pack reload result — 2026-09-23

Status: **VALID THREE-GENERATION DIAGNOSTIC; NO OPTIMIZATION PROMOTED**.
Result directory: `C:/BootOptimBench/results/resource-multireload-20260923a/`.
Source: diagnostic PR #282, commit `9bcf708` (documentation follow-up
`1746e4c`); integration base `b3f0c5f`. The interactive game run used the
packaged wrapper SHA-256
`019D618FCB36595961B78BCD95AC580D139D155BE42CE0511AFCCD9E245E0227`.

The physical HDD laptop ran Oracle Java 21 with its existing 6 GiB G1
settings. The launcher and fixture preparation are outside every reported
interval. The JVM origin was the actual process start, the first BootOptim
marker appeared at JVM uptime 32.611 s, and the first displayed TitleScreen
frame was at 358.149 s. The transaction verified one expected Java process,
the required JVM flags, the absence of `exitOnTitle`, and zero dropped early
rows. The `multi_reload` parser returned valid with no invalid reasons: three
sequential successful generations, 70/70 then 71/71 and 71/71 listener
lifecycles, paired manual requests for generations two and three, and a
post-overlay display marker for each. Resource-pack fallback was not reported.

The user removed `file/Ashen_Custom_Font (1).zip` for the second generation,
then restored it in the same position for the third. The log's effective
external-pack lists confirm this exact intermediate change. Before and after
`resourcePacks` order and `incompatibleResourcePacks` are identical (14
selected packs, ten external ZIPs). The blocks atlas remained 8192×8192×2
in all three generations. There was one additional CreativeCore reload
listener after the initial load, so generation one and later generations
do not have identical listener sets. This run establishes attribution within
one JVM, not a controlled A/B effect of removing the font pack.

| Endpoint, seconds | Initial | ZIP removed | ZIP restored |
| --- | ---: | ---: | ---: |
| `resource_reload` allDone from its start | 167.176 | 317.449 | 357.618 |
| All listener preparations from reload start | 125.255 | 192.809 | 279.999 |
| allPreparations → allDone | 41.919 | 124.640 | 77.619 |
| Manual request → returned-future completion | — | 327.138 | 377.817 |
| Reload allDone → `LoadingOverlay` removal | — | 50.398 | 36.438 |
| Manual request → first display after overlay removal | — | 369.775 | 394.974 |

The manually observed wait was therefore about **6 min 10 s** for removal
and **6 min 35 s** for restoration, measured from each stock public reload
request to the first post-overlay display update. The allDone and future
endpoints precede visual completion. The 50.398/36.438 s overlay tail is
wall time, not listener work or proof of a GPU cause. The future-to-overlay
interval had little additional JVM process CPU and no new GC counter delta
after the second request; the trace does not identify its owner.

ModelManager was the actual preparation gate in every generation: its
prepare-done timestamp preceded the global all-preparations barrier by
10.7 ms, 2.0 ms and 14.1 ms. The disjoint ModelManager preparation chain
contains block-model loading, ModelBakery construction, and model loading/
baking; atlas scheduling overlaps this chain and must not be added to it.

| Inclusive scope, seconds wall | Initial | ZIP removed | ZIP restored |
| --- | ---: | ---: | ---: |
| ModelManager reload | 132.945 | 236.869 | 300.946 |
| `block_models` | 21.906 | 81.103 | 133.518 |
| `block_states` within block loading | 6.352 | 18.229 | 44.668 |
| ModelBakery construction | 61.703 | 74.032 | 63.137 |
| `load_models` including bake | 41.234 | 37.589 | 82.980 |
| `bake_models` within load | 33.901 | 33.543 | 74.624 |
| Atlas schedule/load, overlapping above | 36.984 | 127.531 | 177.631 |
| CIT active load within constructor | 19.531 | 31.358 | 26.336 |
| FancyMenu preload during later reloads | — | 33.292 | 29.315 |

The major preparation growth is `block_models` (21.906 → 81.103 →
133.518 s), although the last generation also stretches `bake_models` from
about 34 to 75 s. The third bake interval accumulated 56.86 s on the JVM GC
time counter, versus 10.29 s in the second; the entire third reload
accumulated 94.55 s, versus 30.37 s in the second and 7.16 s in the first.
That counter co-occurs with low process-CPU/wall ratio during third bake;
it does not alone prove which GC pauses, page faults, or render work caused
each delay. Available physical memory at the all-preparations barrier was
2259, 497 and 1496 MiB respectively. Process-CPU snapshots cover all JVM
threads, not exclusive scope CPU. `atlas_schedule_load` and other listeners
overlap ModelManager; their inclusive times cannot be summed into a critical
path. FancyMenu's interval is nested in the late Minecraft listener apply
lane, not an additive 30 s after it.

## Decision and follow-up

The font-pack toggle did not avoid the full 8192² atlas and model rebuild.
The evidence supports focusing the next investigation on repeat-generation
ModelManager block-resource enumeration/load and its allocation/GC behavior,
with separate counters for resource count, bytes read, cache hits, retained
heap and per-thread CPU. A second premise should instrument the post-allDone
LoadingOverlay/render transition to identify the 36–50 s unassigned visual
tail. Do not propose a cache that crosses reload generations without proving
current-generation pack precedence and invalidation; the older first-load
mechanisms were deliberately scoped to generation one. One interactive run
cannot establish a stable repeat-run trend or a performance win.

The mapped 1.21.1 `ModelManager.loadBlockModels` implementation first runs
`MODEL_LISTER.listMatchingResources(resourceManager)`, then schedules one
`BlockModel.fromStream(reader)` task per discovered model and joins them with
`Util.sequence`. The present `block_models` scope encloses all three steps,
so this run cannot decide whether repeated pack enumeration, JSON reads and
parsing, or join/scheduling accounts for its growth. A targeted next probe
should split those boundaries before attempting a cross-generation reuse
design. The effective pack change was only a font ZIP, but pack precedence
still has to be evaluated per generation; the observed identical blocks-atlas
size is not proof that the model or sprite contents are identical.

After evidence capture, the remote transaction restored the original Prism
configuration SHA-256
`A9744BF7A4C5660F6B58A6FE1FE9309A91ECACC2AC2189BF099DD2A145AA1ECC`
and original BootOptim wrapper SHA-256
`379BC509EFD43A0D2EDF7CFB6BC1E2F99DD1601B3D8E3AB43B00C70F6DEA4989`.

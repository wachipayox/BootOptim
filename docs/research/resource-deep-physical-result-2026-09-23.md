# Physical model-input and overlay deep reload — 2026-09-23

Status: **VALID DIAGNOSTIC; NO OPTIMIZATION PROMOTED**. The source is the
Oracle Java 21 / 6 GiB G1 physical HDD laptop, run
`resource-deep-20260923b`, using the diagnostic wrapper SHA-256
`9E0EF52F31281A9672077F712BAF1B66DD236C92D421242E406CC3A96C2FCADD`.
Its distinct JVM marker defines the origin; launcher setup and fixture staging
are outside the measurement. The game was started, only
`Ashen_Custom_Font (1).zip` was removed and committed with Done, then restored
at its original priority and committed with Done, then the game was closed.
The initial `a` attempt was closed before manual pack changes; it is archived
separately as an invalid **multi-reload** run, not merged into `b`.

The transaction recorded the expected Oracle `javaw.exe` and effective JFR,
startup and variance JVM arguments, then restored `instance.cfg` SHA-256
`A9744BF7A4C5660F6B58A6FE1FE9309A91ECACC2AC2189BF099DD2A145AA1ECC`
and the original BootOptim wrapper SHA-256
`379BC509EFD43A0D2EDF7CFB6BC1E2F99DD1601B3D8E3AB43B00C70F6DEA4989`.
The three reloads completed 70/70, 71/71 and 71/71 listener lifecycles with
one unambiguous start/end each. `multi_reload_deep` passed with no invalid
reasons or warnings. A stale secondary selection checker initially rejected
the new profile *name*; accepting both `multi_reload` and `multi_reload_deep`
made the independent pack check pass. Initial and final `resourcePacks` and
`incompatibleResourcePacks` are identical; the middle effective list omits
exactly the intended ZIP. Raw logs, parsed JSON, state and the readable
38.5 MB JFR are in `C:/BootOptimBench/results/resource-deep-20260923b/`.

## Model input: same sources, increasingly expensive opens

All generations enumerate exactly 44,708 model tasks and 11,484 blockstate
tasks (11,603 underlying blockstate resources), with one stock-caught model
parse failure per generation. The noncryptographic winning-source and stack
fingerprints are identical across the three generations. This establishes
stable IDs and source-pack precedence, **not** identical bytes. Times below
are seconds. Model/state `open` and `parse` are overlapping worker-task sums;
they cannot be added to critical-path wall or compared as exclusive CPU.

| Phase / measure | Initial | ZIP removed | ZIP restored |
|---|---:|---:|---:|
| `block_models` future wall | 26.427 | 84.138 | 191.037 |
| Model `Resource.openAsReader` task sum | 11.770 | 77.899 | 168.506 |
| Model `BlockModel.fromStream` task sum | 8.739 | 5.839 | 14.293 |
| `block_states` future wall | 8.441 | 21.072 | 93.639 |
| State `Resource.openAsReader` task sum | 2.963 | 18.142 | 86.673 |
| State JSON parse task sum | 1.164 | 0.698 | 1.130 |
| Model resource listing wall | 3.131 | 2.445 | 6.865 |
| `bake_models` wall | 32.502 | 42.203 | 74.216 |
| `resource_reload` allDone wall | 164.946 | 281.209 | 428.399 |

The strongest directly instrumented trend is resource opening, not JSON
parsing or enumeration. Pack-level model-open task sums in the third generation
are Decocraft 55.846 s, CreateFood 25.342 s, Create 15.740 s, vanilla
12.049 s and TFMG 11.890 s. These are inclusive task sums across packs and
workers, not sequential critical-path contributions. The `block_models`
future remains the ModelManager preparation barrier as established by the
previous physical run.

## JFR cross-check and concurrent work

The 1,492 s recording contains 27,759 `jdk.FileRead`, 20,411
`jdk.ExecutionSample` and 50,120 `jdk.ObjectAllocationSample` events. The
diagnostic PR #282's `JfrReloadSummary.java` groups events by JVM-epoch windows derived from
the same variance origin. For the `block_models` windows, JFR file-read
durations whose stacks include `ModelManager.lambda$loadBlockModels` sum
approximately 6.3 → 68.1 → 164.6 s, independently matching the direct
model-open trend. Simultaneous blockstate file-read stacks sum 1.1 → 13.9 →
84.4 s; texture-loading stacks and other readers also overlap these windows.
Third-generation `FileRead` events in the block-model window include 164.1 s
of read-event duration on the Decocraft JAR, 63.2 s on Glowing Trim Armors,
55.3 s on CreateFood and 33.4 s on CreateDesignNDecor. Event durations from
different threads and overlapping windows are **not** a disk-busy timeline
or an additive savings ceiling. `UnionFileSystem.byteChannel` dominates the
recorded slow file-read stack family.

The thresholded slow `FileRead` events attributed to model input report only
28,852 → 230,703 → 272,574 bytes read across those same generations, despite
their 6.3 → 68.1 → 164.6 s overlapping duration sums. This is consistent with
small, costly archive reads/page faults during entry opening, but it is **not**
the complete JSON input volume: JFR does not capture fast reads under its
threshold, and ZipFS can materialize an entry before returning a byte channel.

The third `bake_models` span includes about 60.2 s of JFR `GCPhasePause`
overlap within its 74.2 s window (the JVM aggregate GC-time counter increases
57.6 s across the scope). The first/second bakes show about 2.1/5.8 s of
clipped JFR pause overlap. No file reads occur in these bake windows. This is
a separate memory/GC mechanism that an I/O-only change will not fix. JFR
`GarbageCollection` event durations can include concurrent work and must not
be substituted for stop-the-world pause overlap.

JFR allocation samples in the three bake windows repeatedly weight int arrays,
`Matrix4f`, object/float arrays and `BakedQuad` among the largest allocation
classes. The sampled volume is broadly similar across generations, so the
third pause surge is not explained by a clearly larger sampled bake workload.
Sample weights are estimates of allocation, not exact bytes retained or proof
of a particular cache lifetime; a memory redesign needs a live-set/ownership
analysis before claiming a remedy.

The same JFR's `GCHeapSummary`/`G1HeapSummary` events narrow the third-bake
mechanism further. Four `G1New` evacuation pauses start inside the 1049.846–
1124.062 s bake window: 14.844, 15.994, 26.133 and 8.558 s. Their complete
event durations total 65.529 s, while **only 60.182 s overlaps the bake
window**. After-GC old-gen occupancy rises approximately 3,528 → 3,751 →
4,024 → 4,384 → 4,672 MiB across these collections. For comparison, the
first bake starts near 1,755 MiB old-gen occupancy and the second near
3,328 MiB. This shows severe live/tenured pressure during late bake, not a
proved leak or a specific retaining owner. `G1Old` events nearby include
concurrent phases and must not be counted as extra stop-the-world time. An
I/O batch may move or reduce open costs while leaving this GC wall intact;
judge both separately in any candidate A/B.

The JFR also contains 187 `jdk.OldObjectSample` records at recording end.
Many sampled, long-aged byte arrays were allocated through
`InputStream.readAllBytes` → `ZipFileSystem.newByteChannel` during early mod
loading. Their largest sizes match exact uncompressed nested JarJar entries
in the pinned pack: the 70,837,810-byte `rocksdbjni-9.7.3.jar` inside the
user-edited Voxy Reforged JAR appears as three sampled ~70.8 MB arrays;
14,127,503-byte `sqlite-jdbc` inside the same JAR, 25,616,483-byte
CreateAeronautics nested JAR, and other nested JAR sizes also match sampled
arrays. These are **allocation-site/size matches**, not identified reference
roots, exact retained-live-set totals, or proof they alone caused the late
G1 pauses. They establish a plausible long-lived memory-headroom competitor
outside ModelManager. The archive/JarJar ownership question belongs to a
separate loader-memory investigation; it must not be silently charged to
model JSON parsing or to the Decocraft input-batch candidate.

## Reload completion and the overlay

The manual public requests take 283.123 and 433.488 s to their returned
futures, while the corresponding reload allDone values are 281.209 and
428.399 s. The first post-overlay displayed frames occur at JVM uptimes
762.036 and 1244.107 s. allDone-to-overlay-removal is 13.553 and 16.132 s;
the first observed fade begins about 2.303 and 7.522 s after allDone, with
overlay removal a further 11.250 and 8.610 s later. The overlay frame
aggregates show 3,251 and 7,246 rendered frames; cumulative
`Window.updateDisplay` wall is 148.232 and 321.015 s **over the entire
reload**, inside the inter-render gaps, not extra time to add to reload wall.
Maximum individual display calls reach 8.265 and 26.202 s. The post-allDone
JFR windows contain no GC pause and only 2.0/3.2 s of overlapping file-read
event duration; the remaining tail is not proven to be an I/O or GC stall.
There is no basis here to move OpenGL work or to claim a display optimization.

## Next experiment

The isolated two-permit model-input lane in [PR #283](https://github.com/wachipayox/BootOptim/pull/283)
was rejected before A/B: physical model-task sum divided by `block_models`
wall is only 0.804, 0.999 and 0.960 average active tasks, and hosted smoke
recorded just 21 ms total permit wait across 56,311 opens. It reached menu,
but the expected transfer function was absent. No speedup is claimed.

The next distinct premise is to avoid repeat opens for strictly identified,
immutable mod-JAR JSON inputs across reload generations. See
[the bounded-input-cache design](model-input-cache-design-2026-09-23.md).
It requires source identity, resource-pack precedence, reload invalidation,
memory bounds and fail-open behavior; a pack ID or unchanged resource listing
alone is insufficient. Hosted semantic validation and same-branch A/B must
precede any physical comparison. The separate late-bake GC amplification
needs its own allocation/lifetime design. No physical optimization test is
authorized by this result alone.

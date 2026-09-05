# Full-resource laptop runs and startup variance — 2026-09-05

Status: **PROFILED / CAUSE NOT YET ESTABLISHED**. The user explicitly prioritizes
finding the cause of run-to-run variance, not averaging it away.

Base: integration `792d06ec008c5ebae3681dd94f7aeee2c8e5f2a2`.
Related: PR #90 MCEF candidate, #103 resource-contract correction, #107 localized
log clocks, #109 low-cardinality FancyMenu wait CPU diagnostic.

## Workload and provenance

All four runs below passed the selected-resource/order check against hosted
fixture reference run33927602940 and their effective reload logs. Each had one
reload, all ten external ZIPs, 7,920 legacy CIT warnings, the 8192x8192x2 block
atlas, and no BootOptim Mixin failure. This is workload evidence, not pixel or
gameplay verification. Historical runs009–016 did not select those ZIPs and are
not mixed into this table.

Physical runtime: Oracle Java25.0.4, four reported processors, Xmx6144MiB and
unchanged user G1 tuning. Same packaged PR90 benchmark JAR throughout:
SHA256 `c0b20fa7874b6837297b78320910ebe755a250f8278f3bfa8246c0b3a80a5e25`.
The local harness has explicit title-triggered System.exit for automated tests;
it is not production behavior. All runs produced completed runner reports.
Prism was stopped while no Java was active before changing its INI, preventing
in-memory settings from overwriting edits. Effective JVM flags were captured
after launch, not inferred from a filename. No logs were read during Java runs.

018→019→020 was a control→candidate→control sequence without requested reboots,
cache purge or system changes. All three recorded the same kernel LastBootUpTime.
Available physical memory before preparation was approximately 6.05/6.30/6.49GiB,
not a measurement of memory pressure during loading. 017 was an earlier restored-
selection smoke separated by time/user activity; it is not a paired A/B sample.

## Comparable observed results (seconds)

| Measurement | 017 candidate | 018 control | 019 candidate | 020 control |
| --- | ---: | ---: | ---: | ---: |
| JVM to title | 422.797 | 379.661 | 376.479 | 371.601 |
| JVM to mod entrypoint | 127.791 | 125.298 | 119.941 | 119.077 |
| Mod entrypoint to initial reload | 49.634 | 66.918 | 47.971 | 63.290 |
| CEF initialization, nested in preceding row | deferred | 21.852 | deferred | 17.277 |
| Initial reload to blocks-atlas log | 125.195 | 114.414 | 98.519 | 102.844 |
| Blocks-atlas log to FancyMenu preload | 42.934 | 40.529 | 38.027 | 36.697 |
| FancyMenu preLoadAll | 39.875 | 24.349 | 44.337 | 30.300 |
| Preload finish to FancyMenu FINISHED | 17.186 | 4.641 | 14.823 | 13.741 |
| FancyMenu FINISHED to title | 20.182 | 3.512 | 12.861 | 5.652 |

Except for the explicitly nested CEF row, the coarse partitions are serial, not
overlapping listener durations. They are derived from existing log timestamps,
the preLoadAll timer and JVM uptime anchored at mod entrypoint. Millisecond
logging/anchor skew is possible. The blocks-atlas log is not an exact end of
ModelManager preparation or upload. The final two rows are not automatically
FancyMenu self time: other listeners/callbacks can run there.

`tools/laptop-bench/phase_variance.py` reproduces this partition offline, reports
missing milestones/multiple-reload ambiguity and flags impossible intervals.
Its telescoping sum is a consistency property, not independent clock validation.

## What the sequence actually establishes

- 019 is 3.182s faster than control018 but 4.878s slower than control020. The mean
  of the two surrounding controls is 375.631s: candidate019 is 0.848s slower.
  This tiny, order-confounded sample does not establish a physical MCEF speedup
  or regression. Do not promote based on the favorable first pair alone.
- The MCEF switch works: stock CEF runs in controls and stays deferred in the
  candidate. It moves 17–22s of observed pre-title native work, but that amount
  is not an end-to-end benefit estimate.
- Between 019 and020, candidate's preload is 14.037s longer and the final
  FINISHED→title interval is 7.209s longer. These account for much of the offset
  against the removed native initialization. They identify where to look, not why.
- Even same-setting controls018/020 differ by 8.060s overall. Their preLoadAll
  moves in the opposite direction (+5.951s) and post-preload work also grows.
  A uniform hardware-speed multiplier does not explain the observed phase shape.
- Identical pack selection, warning counts and 20 panorama/120 supplier markers
  reject the earlier missing-pack explanation, but do not prove identical async
  scheduling, cache residency, random menu state, I/O, GC or native-resource work.

## Next causal gate

Stop adding MCEF toggles/reboots as the default next test. Hold one configuration
fixed and reuse #109's already-validated wait boundary on the laptop. It measures
current-thread CPU and wall separately for ordinary/slideshow/panorama waits,
without replacing them or adding per-image log traffic. Hosted smoke33975186210
on30e67ce had nonzero132wait coverage: preload4.451s wall/3.114s callerCPU,
panorama3.507s wall/2.277s CPU, slideshow0.810s wall/0.799s CPU. This establishes
active waiting in the hosted surrogate, not the cause of laptop variance.

For the physical diagnostic, add at most a few boundary snapshots of process
CPU, GC totals and memory around preload and the following title tail. If wall
varies without caller CPU, discriminate worker execution versus I/O/scheduling;
if caller CPU tracks the growth, inspect active polling and decode contention.
GC time and process CPU are separate measures; process CPU includes worker/JIT/GC
threads and must not be labeled decoder CPU. Available memory is not hard faults.

Only add bounded JFR/file-read/native samples if these cheap measurements cannot
separate the alternatives. Reuse #65/#47 designs rather than enabling their full
per-resource pipeline counters. Any instrumented runs form their own cohort and
must not be sold as production A/B wins. No speculative cache purge, antivirus
changes, Java tuning, worker multiplication or disabling resource/mod behavior.

## Remaining product boundary

PR90's first consumer performs real initialization synchronously on the client
thread. A late first WebDisplays use could therefore move a pause into gameplay;
no physical first-browser measurement here resolves that risk. A separate source
audit is evaluating preparation before first gameplay without repeating rejected
#78 resource-reload overlap. Startup markers alone cannot close this boundary.

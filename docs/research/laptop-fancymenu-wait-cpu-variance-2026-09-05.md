# Fixed laptop FancyMenu wait-CPU evidence — 2026-09-05

Status: **PHYSICAL DIAGNOSTIC / CANDIDATE NOT YET IMPLEMENTED**

Base: `agent/integration-current` @ `d29a6bad6358c7ff78dadbc5e85bd753c0ad2a54`.

Related: PR #109 (stock wait CPU diagnostic), PR #111 (full-pack phase variance),
PR #113 (fixed-setting process/memory snapshots). This document records one
instrumented physical run; it is not a production benchmark or an A/B result.

## Workload and control of the run

Run ID: `variance-fixed-021` on the Windows laptop benchmark session.

- Oracle Java `25.0.4+7-LTS-189`, four reported processors, `-Xmx6144MiB` and
  the established G1 flags;
- complete exact-pack resource selection: ten external ZIPs in the required
  order, one reload, 7,920 legacy CIT diagnostics, 8192x8192x2 block atlas,
  no resource fallback and zero BootOptim Mixin failures;
- stock MCEF (`mcefFirstConsumerDefer=false`), so this is not a MCEF candidate;
- diagnostic bootstrap JAR from PR #113, SHA-256
  `39620D06E82BBD9EE89EBCFF4371C270EB3CF52FA32C8DA9025EA8F7F2D14D1E`;
- the JAR was restored to the pre-run production benchmark SHA
  `C0B20FA7874B6837297B78320910EBE755A250F8278F3BFA8246C0B3A80A5E25` after
  the process exited.
- the post-run resource-selection checker matched all 15 expected entries in
  the reference order, reported one reload, and returned `valid=true` (this
  validates selection only, not the complete benchmark).

The diagnostic adds only three aggregate snapshots (preload entry, preload
return, first title), the existing low-cardinality wait-family timers, and the
isolated benchmark exit switch. It does not poll logs while Java runs, change
resource packs, purge caches, or modify Windows/Java/driver settings.

## Measured result

The runner reached the main menu in **363,231 ms**. The FancyMenu preload
marker reported:

| Wait family | Calls | Inclusive wall | Current-thread CPU |
| --- | ---: | ---: | ---: |
| ordinary | 2 | 34.050 ms | 31.250 ms |
| slideshow | 10 | 1,783.796 ms | 1,765.625 ms |
| panorama | 120 | 16,359.757 ms | 16,281.250 ms |
| all waits / `preLoadAll` | 132 | 18,298.098 ms | 18,234.375 ms |

The existing production panorama-overlap marker measured `preload_ms=18,288.980`.
The process snapshot over the same interval accumulated **36,046.875 ms CPU**,
with GC elapsed time 0 ms, heap used 3341→3629 MiB, and available physical
memory 1465→795 MiB. The snapshot from preload return to title was 4,047.227 ms
wall and 953.125 ms process CPU. These are aggregate process measurements, not
decoder-only CPU or proof of a GC pause.

The coarse serial partition was internally consistent (sum 363,231 ms):

| Segment | Wall |
| --- | ---: |
| JVM → mod entrypoint | 123,763 ms |
| mod entrypoint → reload marker | 62,991 ms |
| reload → blocks atlas log | 119,922 ms |
| atlas log → preload start | 34,250 ms |
| FancyMenu preload | 18,289 ms |
| preload → FancyMenu finished | 2,042 ms |
| FancyMenu finished → title | 1,974 ms |

## Interpretation

On this 2C4T software-renderer machine, the stock empty-body wait consumes
**99.65% of its inclusive preload wall as current-thread CPU**
(`18,234.375 / 18,298.098`). Panorama waits dominate both dimensions. The
process accumulated about two CPU-seconds per elapsed second during preload,
consistent with decoder/worker execution sharing scarce cores with the render
thread. This is the first direct physical evidence that a cooperative bounded
wait could reduce contention; it does not prove a time-to-menu win.

The run is a separate diagnostic cohort. Its 363.231 s total cannot be compared
as a causal A/B against controls 018/020: it includes probe overhead and a
different cache/scheduling state. It does establish a mechanism ceiling and
justifies a property-gated candidate with the stock completion → failure →
deadline order, explicit interruption handling, and a hosted semantic gate.

The same log still reports six deterministic GLSL compiler errors (OpenGL 4.2
software compatibility path rejecting 4.3–4.6/compute), nine sampler/uniform
warnings, one Voxy config `AccessDeniedException`, and 7,920 CIT warnings. Their
counts/text are stable across 017–021; they remain a separate variance
hypothesis, not an explanation for the wait CPU measured here.

## Decision and reopening rule

- Do **not** merge the diagnostic or activate a wait replacement by default.
- A separate candidate may use `LockSupport.parkNanos` or equivalent only if it
  preserves the exact timeout/error predicate, completion/failure ordering,
  interruption behavior, and resource callbacks. It must pass deterministic
  completed/error/timeout/interruption tests and hosted exact-pack smoke/A-B.
- Only after that gate is a single physical fixed-setting run justified. A
  candidate that saves current-thread CPU but does not reduce critical-path
  wall remains rejected.

The archived local evidence is `laptop-021` in the coordination workspace;
the remote runner state was completed and the original JAR was restored.

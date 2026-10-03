# Ordered ZIP prefix lookup

Clean production promotion; not integrated until its PR merges. Default enabled
on exact FilePackResources + exact ZipFile classes; kill switch
-Dboot_optim.zipPrefixIndex=false. Minecraft1.21.1 listResources enumeration
callsite; optional and stock fallback for unknown paths/providers.

Sort original central-directory entries by name once, find prefix lower bound,
collect contiguous prefix range and restore original ZIP ordinal order. Keep
original entries including duplicate names/directories. Stock path validation,
filtering, fresh suppliers, callbacks and warnings remain. No resource content,
resolution-hit/miss or parsed-model cache. Perpack/open-ZipFileidentity lifetime,
250000entry bound, immutable publication, synchronized initial construction.
Runtime failure disables instance and returns original enumeration. Preserve
ensure-open errors even for empty or in-flight queries. Release on packclose.
No GL/background scheduling change. Concurrent close/build can only publish a
closed snapshot whose access fails open, never a valid stale resource result.

Hosted and physical2C2B show ownerCPU reductions including firstbuild. Physical
savings281.25/484.375ms, hosted343.987/286.159ms. No demonstrated global startup
or reload win. Physical GC/heap mixed: first candidate+12.174s pauses and852MiB
heap, second+1.008s pauses/-121MiB. Retention cost and that outlier stay explicit;
entrycount52510/29indexes both candidates, clear-onclose hardening added.
See ../../research/zip-prefix-final-decision-2026-10-03.md for full boundaries.
No per-query metrics, CPU clocks, verify logging or trial drivers shipped.
Clean runtime gates required before merge; actual helper ordered metadata and
closed-owner contract tests pass offline. Disable on reproducible regression.

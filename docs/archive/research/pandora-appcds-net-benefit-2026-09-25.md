# Pandora AppCDS net-benefit decision — 2026-09-25

**Status: RETIRED for the current generic full-archive design.** Four balanced
physical paired runs (three control→candidate and one candidate→control) show
no repeatable time-to-menu benefit even though the
dynamic archive was proven mapped by HotSpot. Do not run proposed PR #65 or
invest in identity/archive micro-optimizations #51/#62/#63. Reopen only for a
materially smaller archive selected by critical-path class-load evidence, or a
new mechanism that removes critical-path work without paying full-archive map
cost.

## Scope and authority

This is a BootOptim research entry about the private Pandora fork, not a claim
that AppCDS is integrated into BootOptim. The exact tested Pandora integration
head is `c2dc05bfe1167babd52c52dbd0b0f5aa873d5c59`; BootOptim integration is
`b3f0c5f6462a359483883741ac16d2f154868900`. Pandora PR #64 is integrated.
AppCDS PRs #47/#48/#50/#51/#60–#63 remain open and out of integration; diagnostic
PR #65 is open as a draft. Recheck before acting.

## Physical evidence and what it proves

The latest comparable pair on the old HDD laptop used the same Windows session,
build, instance, Java and READY archive, without reboot, in fixed order `auto`
then `plan`, with one run per side. The endpoint was BootOptim's
`bootoptim-startup.log` main-menu marker.

| Measurement | READY `auto` | `plan` STOCK | Candidate − control |
| --- | ---: | ---: | ---: |
| Pandora preflight, before Java | 36.7708 s | 36.1496 s | +0.6212 s |
| BootOptim Java → menu | 428.113 s | 428.114 s | −0.001 s |
| Disjoint subtotal, preflight + Java → menu | 464.8838 s | 464.2636 s | +0.6202 s |

Both Java processes exited cleanly. `plan` still builds and hashes the launch
identity, then returns STOCK before READY archive classification. Thus the
36-second preflight is not the true no-AppCDS cost. The subtotal is only a
within-helper comparison and not click→menu. Fixed order, `n=1`, and page-cache
effects prevent interpreting the +0.62 seconds as a general HDD cost for
checking `ready.jsa`.

This pair is weak and does not provide a true stock control. Separately, the
physical Prism campaign in BootOptim PR #268 resolves the mechanism question:
four paired READY/control runs explicitly opened the dynamic archive and
mapped dynamic regions 0, 1 and 2. The JVM recorded 35,542 shareable classes.
The per-pair AppCDS-minus-control deltas were +3,328, −33,486, +4,258 and
+19,607 ms; their median was **+3,793 ms** (slower with AppCDS). The lone large
win did not repeat. The all-side medians are biased by three control-first
pairs, so the paired-delta median is the decision statistic. The scope was
BootOptim Java marker to `main_menu`; launcher pre-Java overhead was separate.
PR #268 records effective JVM argument checks, run order, logs and archive
mapping evidence.

An earlier 2026-09-21 ledger entry said that `activation=enabled` meant the
archive was “genuinely consumed.” That inference is corrected and superseded
here. The old run's timing remains historical evidence with its original scope,
but its AppCDS-use claim is unverified.

The first-seed and TRAIN runs are not controls: the seed reached menu in
368.488 s; TRAIN reached menu in 928.930 s and ran about 17m29s through clean
exit. Its post-menu interval mixes user/menu time, shutdown and archive dump.
Do not attribute it to AppCDS's startup speed.

## Why the current feature is a product no-go

The launcher computes the exact identity before choosing READY or STOCK.
`PandoraCommand::maybe_bootoptim_prepare` invokes the helper whenever
`BOOTOPTIM_LAUNCH_INTERPOSER` points to a valid executable. The helper's absent
or `plan` mode still calls `build_launch_plan`; it only returns STOCK after that
work. On READY, the helper additionally classifies the cache and strongly
hashes the 285,671,424-byte `ready.jsa` unless a separately validated reuse path
is enabled. Consequently, disabling only `BOOTOPTIM_APPCDS_MODE` is not a
baseline without preflight.

The observed Java delta between READY and `plan` is effectively zero, and the
separate true-control Prism campaign found a +3.793 s paired median on the
Java-to-menu scope. Adding Pandora's measured ~36.8 s recurring preflight would
make the current path still less favorable. The campaigns used different
builds/harnesses and must not be algebraically combined; each independently
fails to show a net win.

The cost might be reducible, but current results do not justify further work:
PR #51 proposes fail-closed NTFS/USN reuse of per-file digests; #62 reduces
repeated journal-boundary queries; #63 targets the full READY archive hash.
These remain unmerged experiments with no end-to-end net-win evidence. The
physical runs already proved that mapping the current full archive is not a
repeatable startup win, so reducing its preparation cost does not rescue the
design by itself.

## Is the JVM mechanism impossible?

No. JDK 21 documents that AppCDS supports custom class loaders and that dynamic
archives can add application classes to the base CDS archive. It also documents
`-Xshare:auto` as “use shared class data if possible,” so normal startup may
continue even when the archive is unusable. `-Xlog:class+load` reports whether
classes came from `shared objects file`; `-Xlog:cds` reports archive handling.
The existence of ModLauncher or custom loaders is therefore not proof of
failure, while transformed/generated classes may still be ineligible.

Sources: [JDK 21 `java` command and unified logging](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html),
[JDK 21 class-data sharing guide](https://docs.oracle.com/en/java/javase/21/vm/class-data-sharing.html).

## Reopening gate

The JVM mechanism works: physical logs proved archive mapping, while hosted
production-JAR validation recorded 4,641 application/custom-runtime shared
class hits and preserved exact resource selection. AppCDS is not categorically
incompatible with this loader stack. The product decision is nevertheless
**NO-GO** for the current full archive: the balanced physical paired result is
slower by a +3.793 s median before counting Pandora preflight.

Do not run PR #65 or spend more on #51/#62/#63. A future reopening needs
critical-path evidence identifying a materially smaller set of classes worth
sharing, followed by a fresh order-balanced physical control/candidate study
that proves both a repeatable Java-to-menu saving and a net launcher-start to
menu saving after all preparation. No current evidence meets that bar.

# Pandora AppCDS net-benefit decision — 2026-09-25

**Status: not promotable; one bounded falsification test remains before final
retirement.** The current AppCDS path has no demonstrated net startup benefit.
Do not merge the identity/archive micro-optimizations or enable AppCDS by
default unless the final test proves both HotSpot consumption and an end-to-end
win after preflight.

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

Most importantly, the prior `activation=enabled` message proves that Pandora
selected READY and passed the CDS flags; it does **not** prove HotSpot mapped
the dynamic archive or loaded any application class from it. No `-Xlog:cds` or
class-source log was captured, so actual VM consumption remains unknown.

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

The observed Java delta between READY and `plan` is effectively zero. If that
remains true, the current user-visible product path is slower than ordinary
launch by roughly the repeated preflight cost. As a rough break-even condition,
with other launcher work held equal, READY must save more than about 36.8
seconds Java→menu to recover its current per-launch preflight. This is an
inference from the observed scopes, not a measured no-helper A/B.

The cost may be reducible. PR #51 proposes fail-closed NTFS/USN reuse of per-file
digests; #62 reduces repeated journal-boundary queries; #63 targets the full
READY archive hash. They are unmerged, default-off/experimental candidates and
have no physical net-win evidence. #63 cannot remove the ~36 seconds shared by
`plan`, because `plan` returns before READY archive hashing. Do not optimize
these paths before proving the JVM mechanism is useful.

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

## One final decision test

PR [#65](https://github.com/wachipayox/BootOptimPandora/pull/65) adds the exact
opt-in `BOOTOPTIM_APPCDS_VM_CONSUMPTION_DIAGNOSTICS=1`, inserting
`-Xlog:cds=info,class+load=info` after identity/cache classification. Head
`a7787a76dd64430d4772bf086570e3925f68df14` passed the focused Windows check and
test in run [36000306173](https://github.com/wachipayox/BootOptimPandora/actions/runs/36000306173).
It is not a release artifact and no laptop run has exercised it.

Use two stages so verbose class logging does not contaminate performance timing
and do not spend four slow-laptop launches proving a mechanism that fails on
the first:

1. **Consumption check:** capture one READY launch with the diagnostic opt-in.
   Require a HotSpot log showing the dynamic archive path mapped and at least
   one non-JDK application class (for example Minecraft/mod code) loaded with
   source `shared objects file`. JDK base-archive classes do not count. If the
   archive is absent or no application classes load from it, stop and retire
   AppCDS without a timing campaign. A stock diagnostic run is optional only if
   the log cannot distinguish the dynamic archive's application classes.
2. **Performance check only if stage 1 passes:** run one A-B-B-A with verbose
   class logging disabled, same current launcher build, Java, pack, instance,
   settings and cache. A uses READY; B removes the interposer environment
   variable and therefore skips both AppCDS and its preflight. Record
   Start/request→Java spawn, BootOptim Java→menu and Start→menu separately.
   Keep launcher setup and post-menu time separate; inspect logs only after the
   timed sequence. Confirm normal Java exit.

This split avoids the `class+load` logger's synchronous per-class I/O from
serving as the performance treatment. Use process/JVM logs for mechanism
verification and uninstrumented timing for the product decision.

## Decision gate

- If the dynamic archive is not mapped or no application classes are
  reproducibly loaded from it, **retire AppCDS**; do not proceed to cache
  optimization.
- If classes are shared but uninstrumented READY does not beat stock in both
  Java→menu and total Start→menu by more than the B-run spread, **retire it**.
- Keep the feature only if it shares a material, reproducible application-class
  set and its uninstrumented end-to-end saving exceeds the full preflight cost.
  Only then resume #51/#62/#63 to lower that measured cost.

Current disposition: **do not ship, do not promote, and do not optimize the
preflight yet.** AppCDS is technically repairable in principle, but the current
cost/benefit evidence makes further engineering unjustified until the single
consumption-and-net-benefit gate above passes.

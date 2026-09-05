# Laptop resource selection audit — 2026-09-05

Status: physical exact-pack performance evidence requires revalidation.

Integration inspected: `ad39b13824d71f6308050e8932f249dd18238923`.
The isolated Prism instance contains the resource ZIPs but did not select them.
Its current options selected only vanilla, fabric, mod_resources and
builtin/add_pack_finders_test. The effective resource reload in run 016 also
contains no external `file/` packs. Presence on disk is not proof of use.

All eleven retained dated latest-log archives (2026-09-04-1 through -7 and
2026-09-05-1 through -4), plus current latest.log, were scanned after Java exited.
Every archive with a reload had no external file packs and zero legacy CIT name
warnings. One archive had no reload at all. This establishes absence throughout
retained evidence; it does not establish when or why selection was originally lost.

Run 016 reached title at 331.047 s and executed stock CEF initialization in
18.629 s. It proves the MCEF kill switch worked in that reduced-resource workload,
not exact-pack performance. Run 014 was separately mislabeled as control despite
emitting the MCEF deferred marker. Historical A/B observations may still describe
their actual workload, but must not be promoted as complete fixture results.

The authenticated GitHub artifact `exact-pack-result-control-1` from run
[33927602940](https://github.com/wachipayox/BootOptim/actions/runs/33927602940)
contains the reference ordered selection of ten external ZIPs, including Glowing
Trim Armors v5.0 and Fresh Animations v1.10.4. Use this artifact to restore only
resourcePacks/incompatibleResourcePacks in the isolated instance, keeping a backup.
Do not overwrite all options: hardware/window choices are separate inputs.

`tools/laptop-bench/check_resource_selection.py` checks exact selection/order
before launch and effective external packs in every reload after Java exits.
It rejects an empty external-pack reference. This is a resource contract check,
not a substitute for artifact identity, effective JVM arguments, visual correctness,
fresh log provenance, successful exit or comparable cache state.

No new physical launch should count as exact-pack evidence until this contract
passes. CIT migration proceeds independently in a hosted fixture copy, with a
matching pack-presence gate. No OS/Java changes or cache purge are needed.

Validation of the checker: it accepts the hosted control options+log and rejects
the original laptop 016 options+log for both selection and effective reload.
The isolated laptop selection was restored from the hosted reference, after
checking that all ten ZIPs exist and all unrelated options remain identical.
The original options were backed up. Restored-selection smoke 017 then reached
the main menu at 422.797 s. The checker passed against the hosted reference for
both saved options and the effective reload. The log contains 7,920 legacy CIT
warnings, the 8192x8192x2 block atlas, and MCEF's deferred marker. Effective JVM
flags were captured separately (defer=true, exitOnTitle=true, Xmx6144m). This is
a successful resource-contract smoke, not an A/B result or visual/gameplay proof.
The laptop was subsequently rebooted by the user; logs were recovered afterwards.

The hosted runner now snapshots fixture options before launch and applies the
same checker after execution, before creating a successful result.json. Its
reference and JSON report are uploaded with diagnostics. A resource fallback
invalidates the run even if an earlier reload or the main-menu marker looked
healthy. No instrumentation is added to Minecraft's measured execution.

# Pandora physical prelaunch attribution follow-up — 2026-09-25

## Run and validity

One diagnostic run used Pandora `agent/integration-current@c2dc05bfe1167babd52c52dbd0b0f5aa873d5c59` plus CLI change `6cf8093ab6397cafa71d5405274268c7baf994f2` from draft PR #66. The Windows launcher artifact SHA-256 was `4dd3b9608f365b8406cf47bf1d18b44f1458970e0fa0b994825274feb0bc812b`. It started Pandora instance `BootOptimLaptop` through `--run-instance-normal`, which exercises the ordinary GUI Normal Start action without a manual Start click.

The matching `bootoptim.launch_probe.v1` and `bootoptim.prelaunch_attribution.v1` sidecars are preserved on the old HDD laptop at:

```text
C:\BootOptimBench\results\prelaunch-attribution-cli6cf8093-20260925-run03
```

SHA-256:

- `captured-launch-probe.jsonl`: `c46daa79a466745c5674283fc5c38a9ffaad404eb0f32a4e1f6d7d6813baf15e`
- `captured-prelaunch-attribution.json`: `ff7ec24bf01127d17ad1735e9cc9ab0d63f778e398c184152d972e9843864ddb`

The root has exactly one `launcher_pre_java.begin/end`, one successful `java_spawn.begin/end`, and `outcome=ok`. Its begin timestamp (`168234796944200`) exactly matches the prelaunch sidecar correlation. The measured Start-to-Java interval is **38.561 s**; it is not Java-to-menu or TTMM. A loader SHA-1 network request was observed after prelaunch, so this run is diagnostic evidence, not a performance baseline or A/B.

## Physical HDD attribution

All child spans below are non-inclusive and inside the parent `prelaunch` span. Do not add the parent to the children.

| Prelaunch phase | Wall | Process CPU / exposed work |
| --- | ---: | --- |
| `prelaunch` | **35.341 s** | 10.203 s CPU |
| `sync` | 48.0 ms | 31.3 ms CPU |
| `load_content` | **14.901 s** | 8.359 s CPU; 157 mods |
| `mods_scan` | 1.43 ms | 312 known paths, 163 directory entries, 6 unknown top-level entries |
| `apply_modpack_and_collect_mods` | 743.0 ms | 157 copies resolved; 437,241,036 inline bytes known |
| `rotate_mods_to_original_mods` | 3.54 ms | succeeded |
| `create_mods_dir` | 1.36 ms | succeeded |
| `apply_copies_to_mods_dir` | **2.260 s** | 157 copies / 437,241,036 inline bytes |
| `extras_copy` — `mcef-cache` | **299.1 ms** | 1,249,107 B / 37 files |
| `extras_copy` — `mcef-libraries` | **16.976 s** | **281,282,882 B / 72 files**, 250 ms CPU |
| Other four extra files | 106.7 ms total | file byte counters unavailable |

The two directory spans were mapped by matching their byte totals against the restored `mods/` tree after the launch: `mcef-cache` was 1,249,107 B and `mcef-libraries` 281,282,882 B. The directory walk was performed after the timed launch. `mcef-libraries` consumed nearly half of prelaunch wall time while using little process CPU, consistent with an HDD-bound copy in this run. `load_content` is the other large measured scope and still contains work not split by this sidecar.

## Autoclose result

The watcher detected the first Java main-window handle at `12:09:28.549+02:00` and `CloseMainWindow()` returned true. The JVM remained alive after the watcher's 45-second graceful wait, so the watcher did not force-kill it. A later observation at `12:13:04+02:00` found no Java process, `original_mods` absent, and `.minecraft/mods` present, confirming normal layout restoration. This validates the graceful close request and eventual shutdown, not a sub-45-second process exit or a time-to-menu endpoint.

## Decision and next gate

This is one physical sample, with no control/candidate pair; it does not justify shipping an optimization. The strongest new launcher hypothesis is to reduce repeated staging of `mcef-libraries`, with `load_content` a separate follow-up attribution target. MCEF's [upstream source README](https://github.com/CinemaMod/mcef) describes its downloader for the java-cef/CEF binaries, so the native directory must be treated as version/platform-sensitive and potentially updated. Do not simply omit the copy, hard-link mutable files, or repurpose `original_mods` as a cache.

PR #26 already sets the safe reuse boundary: recompute current sync/content/modpack state, keep `original_mods` as user/recovery state, and place any prepared layout in separate launcher-owned state with exact identity, clean publication/restore, and full stock fallback on uncertainty. Open PRs #32–#36 add persistent-layout control-plane/client-reconciliation pieces but do not currently connect a Start fast path; avoid duplicating that design work.

Before implementation, inspect whether MCEF can mutate this tree during launch and define its version/platform/content identity and restore semantics. Then use alternating physical stock/candidate runs from the same successful Java-spawn root, reject unmatched downloads/updates, and validate MCEF library refresh plus user-extra/`original_mods`/interrupted-launch recovery. Promote only on a repeatable Start-to-Java win with semantic validation. Keep Java-to-menu separate.

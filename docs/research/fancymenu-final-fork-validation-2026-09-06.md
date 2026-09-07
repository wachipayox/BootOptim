# FancyMenu final fork validation on the physical laptop — 2026-09-06

Status: **The first pair was invalid for the retained active-layout optimization; the corrected pair is complete and the optimization remains retained.**

The earlier pair below must not be read as evidence against the active-layout
filter. The candidate staging omitted the required
`preload_only_initial_layout_resources = true` option, so it exercised only the
new global prelaunch path and intentionally loaded all 20 configured panoramas.
That is a different hypothesis from the already validated active-layout
optimization (which should log `kept 1/skipped 21`). The physical test was
reopened with the option explicitly present and is recorded below.

This was the final physical A/B requested before leaving the controlled FancyMenu fork aside. Both runs used the same exact-pack laptop instance, the same production BootOptim build from integration `b0aa2472d58e3afc56a380e026c99ffe87000f22` (SHA-256 `70744A8B733C1FE16D56C854AE34E4ED5A6B356EF2F06266F3980908080FDB01`), `mcefFirstConsumerDefer=true`, and `exitOnTitle=true`. The candidate used the fork build `711914ba5` (SHA-256 `2815FE7250C3CD8EF91353EA4BBCB8C152D65C816A27E0882B9AC46E9F6F6EB2`); the control used the stock `3.9.0-wedit` JAR (SHA-256 `8E1C68F2C91AED02057209252BBE221BF3B019C4E82FB20FE35809BAC2C08DB8`). Resource-pack selection and order were preserved.

## Results

| run | FancyMenu | mod entrypoint | total to main menu | result |
| --- | --- | ---: | ---: | --- |
| `fancymenu-final-candidate-20260906b` | fork `711914ba5` | 118,150 ms | **380,136 ms** | menu reached, autoclose |
| `fancymenu-final-control-20260906` | stock `3.9.0-wedit` | 125,436 ms | **379,089 ms** | menu reached, autoclose |

Candidate minus control was **+1,047 ms (+0.28%)**, which is not a meaningful end-to-end improvement on this hardware. The candidate entered the run 7.3 s faster before the measured reload, but its reload interval was about 4.3 s slower, so the small final difference is not attributable to the fork.

The candidate's new global background prelaunch did run:

- `Parallel background prelaunch: panoramas=20, slideshows=2, suppliers=130, failed_suppliers=0`;
- BootOptim's existing panorama marker reported `preload_ms=39585.136` and 120 suppliers.

The stock control reported the same BootOptim marker with `preload_ms=15593.919`. Thus the new fork path did extra work (about 24 s more inclusive preload) without moving the menu critical path. This is consistent with the earlier causal result: supplier launch/PNG decode can overlap resource preparation, but launching all configured sources does not shorten the enclosing gate for this final pack.

Both logs reached the menu with no BootOptim Mixin failure. The known Voxy config `AccessDeniedException` appeared in both runs and remains unrelated noise. The pair was sequential without a Windows reboot, so it is a hardware-sensitive operational comparison, not a cold-cache effect-size estimate.

## Provisional disposition of this pair

Do not promote the latest global prelaunch path from this pair: it did extra
inclusive work without moving the menu critical path. Do **not** use this pair
to close the active-layout path. Keep the previously validated active-layout
work provisionally retained while the corrected option-enabled pair runs.

The laptop was restored to the original BootOptim JAR (`C0B20F...`), original
FancyMenu JAR (`8E1C68...`), original `instance.cfg`, and original `options.txt`
after this invalid-for-active-layout pair.

## Corrected option-enabled pair

The rerun staged the fork with the complete resource list **and** the explicit
`B:preload_only_initial_layout_resources = 'true';` option. It also used the
same production BootOptim JAR, the same `mcefFirstConsumerDefer=true` and
`exitOnTitle=true` settings, and one FancyMenu JAR per run.

| run | FancyMenu | filter/launch evidence | mod entrypoint | total to main menu |
| --- | --- | --- | ---: | ---: |
| `fancymenu-final-layout-candidate-20260906` | fork `711914ba5` | `kept 1/skipped 21`; 1 panorama, 6 suppliers | 125,503 ms | **354,766 ms** |
| `fancymenu-final-layout-control-20260906` | stock `3.9.0-wedit` | BootOptim full-list prelaunch: 20 panoramas, 120 suppliers | 123,367 ms | **362,408 ms** |

The corrected candidate was **7,642 ms faster (2.11%)** end-to-end. The
candidate's post-entrypoint interval was 229,263 ms versus 239,041 ms for the
control (−9,778 ms), while its entrypoint itself was 2,136 ms slower. The
FancyMenu reload gate ran from 16:36:03.032 to 16:36:04.370 (1.338 s) in the
candidate. The control ran from 16:45:38.589 to 16:45:53.192 (14.603 s), with
the existing BootOptim all-source marker reporting 14.200 s of inclusive
panorama preload.

This pair explains the apparent contradiction rather than closing the whole
FancyMenu lane. The previous “latest hybrid” pair had the filter option absent
or false, so both sides effectively loaded all 20 panoramas; it did not test
the retained optimization. Once the option is actually enabled, the fork
again skips the 21 unselected sources and moves the reload gate. The gain is
smaller than the earlier 52.867 s control-002/active-layout-006 pair because
this control is on the newer BootOptim head whose stock FancyMenu path already
prelaunches all 20 panoramas, and because the laptop remains noisy; it is not
evidence that the active-layout logic stopped working.

The fork's overload also explains why the candidate has no
`BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD` marker: the fork calls
`preLoadAll(long, selectedSources)`, while the existing BootOptim mixin targets
the stock one-argument overload. The fork performs its own equivalent
six-supplier prelaunch for the selected source, so this is an observability/API
shape difference, not a missing preload in the candidate.

## Corrected disposition

FancyMenu is **not** a closed alley. Retain the active-layout optimization in
the private fork (opt-in, with its existing fail-open selection/commit guards)
and stop pursuing the generic all-source prelaunch as an additional gain for
this pack. No further laptop A/B is justified for the same mechanism unless
the pack's selected-layout/resource contract changes; future work should move
to BootOptim/P0.2 or to a genuinely different FancyMenu scheduling premise.

## Good-PC beta staging audit — 2026-09-06

The exact beta instance was later found to contain an intermediate `3.9.0-wedit`
JAR: its `ResourcePreLoader` includes the active-layout filter, but not the
newer `preLaunchBackgroundSources`/parallel-preload implementation. Its
`options.txt` correctly has `preload_only_initial_layout_resources = true` and
the run logged `kept 1/skipped 21`. Any good-PC visual observation from that
session therefore must not be presented as validation of the latest fork build;
the latest locally built NeoForge `fancymenu-1.0.0-all.jar` should be staged only
after the running instance is closed, with one active FancyMenu JAR.

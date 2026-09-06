# FancyMenu global panorama prelaunch — 2026-09-06

Status: **validated as a generic full-list scheduling win; not applicable to the
current final pack after its active-layout filter**.

## Premise

The retained BootOptim FancyMenu hook launches the six suppliers of one
panorama immediately before FancyMenu waits for that panorama. FancyMenu then
waits each panorama in the configured list before it reaches the next one. The
source-level inspection shows that local PNG decoding is asynchronous, so the
remaining scheduling opportunity is to launch all configured panorama
suppliers before the first ordered wait.

This branch adds `-Dboot_optim.fancymenuGlobalPanoramaPreload=true`. It is
disabled by default and does not replace FancyMenu's original loop, timeout,
failure checks, resource identity or render-thread texture registration. A
reflection failure logs a warning and leaves stock behavior in place.

## Decision gate

Run the hosted exact-pack A/B first with the property set only on the
candidate. Require identical selection/atlas/Mixin checks and improvement in
both time-to-main-menu and the enclosing `reload -> FancyMenu` interval. A
microphase or CPU improvement alone is insufficient. Do not request another
laptop launch unless the hosted result is coherent and the mechanism still
applies to the final FancyMenu fork/configuration.

The user's current FancyMenu fork also has the separate
`preload_only_initial_layout_resources` option, which may skip unselected
panoramas entirely. If that option is active in the tested artifact, the
global hook must report zero or a filtered set; its result must not be compared
with the historical full-list workload.

## Hosted exact-pack result (PR #146)

The corrected candidate (commit `fecfdc1`) passed the exact-pack build,
startup, fixture and 3x3 A/B gates. The first A/B was discarded because the
overload hook had an invalid descriptor and Mixin rejected it; no timings from
that run are evidence.

On the valid run (`34034362376`), the candidate launched all 20 configured
panoramas / 120 suppliers before the ordered waits. Medians were:

| metric | control | candidate | candidate − control |
| --- | ---: | ---: | ---: |
| time to main menu | 92,720 ms | 90,047 ms | **−2,673 ms** |
| reload → FancyMenu finish | 42,655 ms | 41,448 ms | **−1,207 ms** |
| FancyMenu panorama preload | 4,229.2 ms | 3,168.7 ms | **−1,060.5 ms** |

All six jobs reached the main menu and reported zero BootOptim mixin errors.
This confirms the causal hypothesis for the historical full-list workload:
cross-panorama overlap moves PNG decoding from a serial-per-panorama critical
path to one shared in-flight set.

It does **not** justify enabling the hook in the current final pack. Its
`preload_only_initial_layout_resources` run kept one background source and
skipped 21. The existing per-panorama hook already starts all six images of
that one selected panorama before waiting, so a global hook has no second
panorama to overlap. The hosted win is therefore a real generic mechanism but
not an additional win for the shipped configuration. No laptop run is needed
for this branch unless the pack later enables multiple selected panoramas or
removes the filter.

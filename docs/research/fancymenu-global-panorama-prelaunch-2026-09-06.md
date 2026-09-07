# FancyMenu global panorama prelaunch — 2026-09-06

Status: **validated as a generic full-list scheduling win; not an additional
optimization for the current final pack**.

The retained BootOptim hook already starts all six local PNG suppliers of the
current panorama before waiting for them. The remaining historical bottleneck
was that FancyMenu waited one panorama before starting the next. An opt-in
diagnostic hook in PR #146 starts every configured panorama supplier before the
original ordered loop; the original timeout, failure handling, resource
identity and render-thread registration remain unchanged.

The corrected hosted exact-pack 3x3 A/B (`34034362376`) passed all startup and
Mixin checks. Medians:

| metric | control | candidate | delta |
| --- | ---: | ---: | ---: |
| time to main menu | 92,720 ms | 90,047 ms | **−2,673 ms** |
| reload → FancyMenu finish | 42,655 ms | 41,448 ms | **−1,207 ms** |
| panorama preload | 4,229.2 ms | 3,168.7 ms | **−1,060.5 ms** |

The candidate reported 20 panoramas / 120 suppliers, proving that the win is
cross-panorama overlap rather than a measurement artefact. The first A/B was
discarded because an overload hook had an invalid Mixin descriptor; it is not
evidence.

The final `3.9.0-wedit` configuration is different: its
`preload_only_initial_layout_resources` path kept one source and skipped 21.
The existing per-panorama hook already overlaps the six faces of that one
source, so there is no second panorama to overlap. Therefore the generic hook
stays diagnostic/default-off and should not be promoted for the current pack.
Reopen only if the pack selects multiple panoramas or disables the layout
filter.

# CITResewn base item-model parse cache — 2026-09-06

Status: **ACTIVE experiment**

## Motivation

The physical laptop run `laptop-fancymenu-active-layout-002` reached the menu at
`393.533 s`. Its resource reload started at `23:07:11.611`; CITResewn emitted
7,920 legacy `nbt.display.Name` diagnostics and then reported `Loading item CIT
models...` at `23:08:23.772` and `Linking baked models to item CITs...` at
`23:09:46.000`. The isolated P0.2 profiler attributed roughly 150 s to the
ModelManager gate, with about 137 s in block-model/ModelBakery/loadModels work.

The deployed CITResewn JAR is `(svfr) CitResewn {v0} [1.21.1] [MAINLOC].jar`.
Its `TypeItem.loadUnbakedAssets` parses every targeted base
`models/item/<item>.json` for every loaded CIT before it reads the override
list. The same small JSON is therefore opened and decoded repeatedly. This is
particularly sensitive to an HDD page cache and explains why the laptop wall
time can move substantially between runs without a code change.

## Candidate mechanism

The experiment adds an optional Mixin bridge for the external CITResewn class:

* cache only the `BlockModel.fromStream` result for direct `models/item/*.json`
  reads made by `TypeItem.loadUnbakedAssets`;
* keep all custom CIT model/texture loading on the stock path;
* clear the cache at the start of every `ModelBakery` construction (the reload
  boundary), and report hit/miss counts only in startup-profile runs;
* fail open if CITResewn is absent, its method shape changes, or the JVM property
  `-Dboot_optim.citresewnItemModelCache=false` is supplied.

The cached base model is only inspected for its override list in this path; it
is not inserted into ModelBakery's model maps and is never mutated by the
candidate. This is the semantic invariant that must be rechecked against the
exact JAR before promotion.

## Validation gate

This branch is not production. First run an exact-pack hosted smoke/A/B and
inspect the `BOOTOPTIM_CITRESEWN_BASE_MODEL_CACHE` marker. The candidate must
show a high hit rate, reduce the ModelBakery/CITResewn span and improve
time-to-menu without new Mixin failures. Then repeat one control/candidate pair
on the physical laptop with the same pack and no restart between the pair only
when the harness requests it; a second cold/after-reboot sample is needed to
separate HDD-cache variance from the optimization.

Do not treat the 7,920 warning lines as the optimization target: muting them can
reduce log overhead but does not remove the repeated model parsing.

## Initial hosted evidence

The exact-pack A/B workflow (run `34054864798`, three fresh VMs per side) loaded
the same CITResewn path and reported `requests=3960`, `hits=3944`,
`misses=16`, `hit_rate_percent=99` for every candidate run. There were no new
Mixin errors and the atlas/block counts were unchanged. Candidate median
time-to-main-menu was 88.511 s versus 91.770 s for control (-3.259 s,
-3.55%); the resource-reload-to-FancyMenu median was 39.178 s versus 41.998 s
(-2.820 s, -6.71%). The hosted machine has much faster storage than the target
laptop, so this is confirmation that the bridge is active rather than a
physical-laptop equivalence claim.

## Physical variance evidence to reproduce

Before this cache, the laptop's `Loading item CIT models` → `Linking baked
models to item CITs` span was 40.1 s, 82.2 s, 37.6 s, 42.9 s and 33.3 s in
the available runs. The corresponding total menu times ranged from 335.578 s
to 505.143 s, with one contaminated 1,272.775 s run. This spread is consistent
with repeated HDD reads/page-cache state and GC on a two-core machine. A
control/candidate pair on the same boot is therefore required before claiming
that the cache removes variance; a later cold/after-reboot sample is needed to
separate page-cache effects from the code-level reduction.

The 7,920 legacy-name warnings are a separate, secondary source of serialized
logging work. `mute_warns=true` would hide diagnostics but must not be counted
as a model-cache result; it should be tested independently only after this
candidate's physical A/B.

## Physical laptop A/B (6 GiB heap, no reboot)

The valid control run reached the main menu in `381.011 s`; its CITResewn
load-to-link span was `64.092 s`. A first candidate artifact was discarded
because its wrapper still contained the pre-fix non-static constructor hook;
the log showed the Mixin rejection and no cache marker, so its `426.378 s`
total is not evidence.

The corrected candidate reached the menu in `444.478 s`. It emitted
`requests=3960 hits=3944 misses=16 resource_open_bypasses=3944` with no Mixin
failure. Its CITResewn span was `52.665 s`, an isolated reduction of
`11.427 s` (`17.8%`) despite the total startup moving in the opposite
direction. During the run the Java process grew to about `5.4 GiB` private/
working-set territory on the laptop's `8 GiB` system, making total startup a
poor attribution metric. A follow-up with a 4 GiB Prism heap is in progress to
test whether avoiding memory pressure narrows the tail and makes this win
repeatable.

The 4 GiB follow-up reached the menu in `471.979 s`; the CITResewn span was
`61.9 s` and the cache marker still showed `3944` open bypasses. It therefore
did not improve this workload over the 6 GiB candidate (`444.478 s`, `52.665 s`
CIT span). The heap change is rejected as a BootOptim recommendation: on this
two-core laptop the smaller heap adds GC pressure rather than removing the
dominant storage/model variance. The benchmark instance is restored to its
original 6 GiB configuration after the run.

## Reopening / rejection criteria

Reject or redesign if the target method no longer has the direct base-model
parse shape, if cached models are observed to be mutated, if CIT output differs,
or if the candidate only reduces task-sum CPU while the critical-path wall is
unchanged. A future direct CITResewn fork can replace this bridge with a shared
immutable override index if the bridge proves useful but remains too invasive.

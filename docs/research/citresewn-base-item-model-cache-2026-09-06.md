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

## Reopening / rejection criteria

Reject or redesign if the target method no longer has the direct base-model
parse shape, if cached models are observed to be mutated, if CIT output differs,
or if the candidate only reduces task-sum CPU while the critical-path wall is
unchanged. A future direct CITResewn fork can replace this bridge with a shared
immutable override index if the bridge proves useful but remains too invasive.

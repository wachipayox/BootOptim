# CITResewn base-item cache target-drift audit — 2026-09-09

Status: **CLOSED / NO RUNTIME CHANGE**

## Question

PR #221 observed that a current public NeoForge CITResewn source tree uses
`com.github.citresewn.defaults.cit.types.TypeItem`, while BootOptim's retained
PR #151 bridge targets `schm.shsupercm.citresewn.defaults.cit.types.TypeItem`.
The #221 discussion also stated that its valid hosted smoke emitted no
`BOOTOPTIM_CITRESEWN_BASE_MODEL_CACHE` marker. This audit checks whether #151
became inert through package drift or became semantically invalid.

Authority for this audit is `agent/integration-current` at
`fa6df8bc8f74aae32338f521bf845a5730ac634b`. No runtime code is changed.

## PR #151 contract rechecked first

PR #151 does not cache the whole CIT lifecycle or the mutable CIT replacement
models. Its redirects are limited to the direct base-item reads performed by
`TypeItem.loadUnbakedAssets`: `ResourceManager.getResource`, `Resource.open`,
and `BlockModel.fromStream` for `models/item/*.json`. The cached `BlockModel` is
used only for inspecting its override list. Custom CIT models remain on their
stock loading/mutation path. The cache is cleared at each `ModelBakery`
construction, so it is scoped to one model/resource reload generation.

The retained historical evidence is hosted exact-pack run `34057909967`
(3+3 fresh VMs), where the final open-bypass candidate reported 3960 requests,
3944 hits, zero BootOptim Mixin errors and unchanged `8192x8192x2` block atlas.
That campaign measured a main-menu median of 65.006 s candidate versus 75.609 s
control and reload-to-FancyMenu 28.709 s versus 33.907 s. The physical-laptop
same-boot pair only established an isolated CIT load/link reduction and did not
establish a total-startup win; it is not reused as current timing evidence.

## Current exact-pack evidence contradicts the package-drift premise

The valid #221 hosted exact-pack smoke is run `34295167385`. Its uploaded
`run-pack-benchmark/logs/latest.log` contains:

`BOOTOPTIM_CITRESEWN_BASE_MODEL_CACHE requests=3960 hits=3944 misses=16 hit_rate_percent=99 entries=16 resource_open_bypasses=3944`

The same run reached the main menu, reported zero BootOptim Mixin errors and an
`8192x8192x2` block atlas. Therefore the PR #151 redirect was active in the
current pinned exact-pack workload. The statement in the #221 discussion that
no cache marker was emitted is incorrect for the uploaded artifact.

Because the only BootOptim TypeItem mixin on the authority tree is the retained
`@Pseudo` target for `schm.shsupercm...TypeItem`, the exact-pack runtime cannot
be treated as proof that the public `com.github...TypeItem` class is its loaded
implementation. The exact-pack log reports the deployed mod only as
`CITResewn 0 (citresewn)`, so a public repository's semantic version is not a
safe runtime-version discriminator for this fixture.

## Public current-source semantic check

For future compatibility, the NeoForge source at
`CancriRecoleta/CITResewn@8cca0f127f3898472e2109f6ac6a797253756893`
(`minecraft_version=1.21.1`, `mod_version=0.7.22`, package group
`com.github.citresewn`) was inspected without using it as the exact-pack binary
identity.

Its `TypeItem.loadUnbakedAssets` still contains the same narrow repeated base
item-model pattern in both relevant branches: construct
`models/item/<item>.json`, obtain/open the resource, parse it with
`BlockModel.fromStream`, and only read `getOverrides()` / each override's model
and predicates to build the local override-condition map. The method's custom
CIT assets are loaded through separate `loadUnbakedAsset` calls, where models
and texture/override state are mutable; those are outside PR #151's
`BlockModel.fromStream` redirect.

Thus the narrow #151 semantic invariant has not disappeared in that public
source. What is unproven is binary identity: the exact pack currently exercises
the historical target, not the new public package.

## Decision

Do **not** retarget PR #151 on the current exact pack and do not create a new
cache. The supposed package-drift failure is not reproduced: the current valid
hosted artifact proves the retained bridge applies and achieves the same
3960/3944 request/hit shape as the historical candidate.

Do not extend the cache across `ActiveCITs.load`, across `ModelBakery`
generations, or into `loadUnbakedAsset`; those paths own mutable CIT/resource
lifecycle state. No callback, resource reload order, thread, GL/render ownership,
item-model result or CIT state is changed by this audit.

If the exact pack later upgrades to a CITResewn binary that actually contains
only `com.github.citresewn...TypeItem`, reopen with the upgraded JAR fingerprint
as the version pin. At that point a default-off compatibility candidate may add
that target only after a smoke proves the marker and reload-scoped generation
behavior on the upgraded binary, followed by semantic model/CIT validation and
a fresh comparable hosted 3x3 A/B. Public source package names alone are not a
sufficient reason to retarget production code.

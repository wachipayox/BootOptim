# Physical model/renderer split — 2026-09-22

Status: **DIAGNOSTIC PREPARED; runtime result pending**.

Integration refreshed: `b3f0c5f`. Follows partial run
`resource-initial-20260922a`: ModelBakery 76.360 s, renderer slot 18.658 s,
but process ownership failed and early stdout markers were missing.

This reuses #217/#221 constructor callsites and #190 renderer callsites.
Read #190 and #194 bodies, results and lifecycle audit before implementation:
EMF's shared mutable provider context and AddLayers current-object semantics
still prohibit generic concurrency/deferral. The new premise is physical
attribution inside stock order, not another renderer optimization candidate.

## Seven added scopes

- `cit_active_load`: exact `schm.shsupercm.citresewn.cit.ActiveCITs.load`;
- `bakery_blockstate_registration`: stock `loadAllBlockStates`;
- `bakery_parent_resolution`: stock constructor parent-resolution forEach;
- `bakery_additional_model_event`: stock NeoForge additional-model event;
- `entity_provider_create`: stock entity-provider factory aggregate;
- `player_provider_create`: stock player-provider factory aggregate;
- `entity_add_layers_post`: stock dispatcher ModLoader event call.

Each records coarse wall/process CPU/current-thread CPU/GC snapshots using the
existing variance probe. There are no per-item/model/provider clocks, stack
walks, resource copies, caches or extra executors. All original calls execute
once in place; futures and listener order remain unchanged. The optional CIT
HEAD/RETURN probe leaves failed calls unmatched rather than fabricating success.
Every probe remains disabled without `profileStartupVariance=true`.

The current laptop CIT JAR was fetched solely for signature inspection and
SHA-256 confirmed as `315b46f2a78d5298426fb594558873ac19abbc499f77578f6cd4f98e6809e6a9`.
It matches the previously audited historical fixture class lineage. No CIT
source or JAR is republished or changed.

Constructor residual is **not automatically CIT cost**. Determine whether
the observed CIT interval falls inside the constructor's actual timestamps;
mixed constructor injections can affect nesting. Subtract interval unions,
not sums of possibly overlapping scopes. Unmeasured item-CIT/model work and
ordinary constructor work remain residual until attributed.

## Early evidence and validator contract

Bootstrap coarse stdout records are additionally retained in a bounded
128-row in-memory buffer. `boot_optim.varianceBootstrapOutput` supplies an
absolute, previously absent output path. On normal JVM shutdown the buffer
writes with CREATE_NEW and a dropped-row count. It performs no file writes
during startup. Forced exit or write failure means incomplete evidence, not
a passing run. Console emission remains unchanged.

Parse the early output and latest.log together, sorted by monotonic time.
Do not overwrite one stream with the other; bootstrap and GAME have separate
sequence counters. The existing parser keys scopes by phase and scope ID.
Use explicit `--profile resource_split`: the legacy FancyMenu preload hook
is not required for this fork, but all seven new start/end scopes are. All
other legacy origin/reload requirements remain. Verify zero dropped rows,
balanced scopes, actual process ownership, resource order and listener health
separately before classifying the run as valid.

## Validation and operational contract

- Local Gradle build/tests pass, including bounded-buffer order/overflow and
  refusal to overwrite previous evidence.
- All 33 Python tests pass; resource_split cannot silently accept absent new
  hooks or loosen the legacy profile.
- Both real PowerShell detector functions pass slash/case/native-path/sibling
  regression tests under Windows PowerShell 5.1 locally and on the laptop.
- Runtime Mixin activation on the exact physical pack is **pending**. This
  diagnostic is not a production promotion or a performance comparison.

Run ID `resource-split-20260922b`, remote directory
`C:/BootOptimBench/resource-split-20260922`, transaction state beneath
`state/resource-split-20260922b/state.json`.
Packaged wrapper SHA-256:
`741bad8b3e0654f8d041bb4de10582d786d17078e603299bb5834128ddd30245`.

Prism 11.1 / existing BootOptimBench instance, Oracle Java 21.0.9 / 6144 MiB;
unchanged original JVM options plus variance enable/output-path properties.
The corrected transaction preserves/restores exact wrapper/config bytes.
The user will notify completion; end the turn immediately after dispatch.
No log polling or additional diagnostic launch while Java runs. Automatic
exit remains first display update after TitleScreen opening, not proof of a
navigable TitleScreen. On completion fetch state, early log, latest/startup
logs and options before postflight; do not rerun merely because a hook failed.

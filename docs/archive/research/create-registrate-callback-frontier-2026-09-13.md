# Create / Registrate callback-registration frontier — 2026-09-13

## Status

**PROFILED FRONTIER — no behavior-changing candidate yet.** This record turns
the remaining Create construction evidence into a narrow next diagnostic and
states the only architectural route worth evaluating afterwards.

## Established causal evidence

The contract-valid exact-pack observer in PR #246 establishes that Create
`6.0.10` is on the FML construction critical chain.  In its most granular
accepted run, `Create.onCtor` was 1,067.177 ms and its ordered registration
family slice was 1,003.926 ms. `AllBlockEntityTypes.<clinit>` owned 430.348 ms
of the `block_entities` interval (452.906 ms); its 115 runtime entries include
the 114 stock entries plus Sable's `redstone_contact` injection.  These are
wall-attribution values, not savings estimates.

The existing per-entry observer shows material unevenness (`brass_tunnel`
43.437 ms, `sticker` 24.775 ms, `chain_conveyor` 20.682 ms, etc.), but each row
is a contiguous interval ending at `BlockEntityBuilder.register()`. It includes
class initialization, builder construction, visual/renderer configuration,
valid-block collection, callback registration and final Registrate acceptance.
It cannot tell which of those costs is removable.

## Source-level finding

Exact Create source `ac0c444d9828da3453ae8cc65338e8de063286fb` configures most
block-entity entries through `CreateBlockEntityBuilder.visual(...)` and
`BlockEntityBuilder.renderer(...)`. On the client both immediately call
`OneTimeEventReceiver.addModListener(..., FMLClientSetupEvent, ...)` (the
former through `CatnipServices.PLATFORM.executeOnClientOnly`).

The exact Registrate `MC1.21-1.3.0+67` lineage
`fd0f4e7fa1e53090cbe2024483b82743366d4c87` stores listeners while the mod bus
is unavailable, then installs an individual `OneTimeEventReceiver` for every
pending callback. This is a plausible structural allocation/listener
registration cost, but it is not yet a demonstrated owner of the 430 ms.
`AbstractRegistrate` and its builders are mutable and explicitly not generally
thread-safe, so neither worker construction nor a generic reorder is allowed.

## Next diagnostic (distinct from PR #246)

On the exact runtime shape only, add an opt-in observational probe that follows
the transformed `AllBlockEntityTypes.<clinit>` and records per builder name:

1. `CreateRegistrate.blockEntity` / builder construction;
2. `CreateBlockEntityBuilder.visual`;
3. `BlockEntityBuilder.validBlocks` / `validBlock` accumulation;
4. `BlockEntityBuilder.renderer`;
5. `BlockEntityBuilder.register` / Registrate `accept`.

The probe must use entry/return observation only, retain stock calls and
exceptions, pin Create/Registrate/Sable topology, and report non-overlapping
per-entry segments. It must separately count and time client-only
`OneTimeEventReceiver.addModListener` calls made while this class initializer
is active. It must not use reflection per call, force-load visual/renderer
classes, change the event bus, or assert that an interval is pure.

Acceptance requires the same #246 contract (exact versions, one Create worker,
115 transformed entries, source order, zero observer errors) and a hosted
exact-pack smoke. This is attribution only: no A/B or laptop run is justified.

## Candidate only if the diagnostic proves the premise

If callback registration, rather than arbitrary first-touch class
initialization, is materially dominant, evaluate a **Create-scoped Registrate
batching design**. It may combine pending callbacks only by the exact
`(owner, event class, priority)` tuple and execute their original consumers in
their original registration order, exactly once, on the same event bus/event.
It must preserve exceptions and listener visibility/order relative to every
other callback. A generic BootOptim interception is not acceptable unless that
global ordering proof exists; a version-pinned source-compatible Registrate
fork is the safer implementation lane if the measurement warrants it.

This is not permission to batch today. A single listener registered at the
first Create entry could run later callbacks before third-party callbacks that
were formerly interleaved, which would violate the lifecycle contract. The
candidate must demonstrate the event-bus ordering equivalence in a focused
harness before exact-pack A/B and then menu/world validation.

## Explicit non-goals

- Do not parallelize Registrate, `AllBlockEntityTypes`, or client setup.
- Do not pre-initialize visual, renderer, packet or codec classes on workers.
- Do not treat the 430 ms inclusive class-initializer wall as recoverable.
- Do not re-open `AllPackets`; PR #255 showed the actual registration loop is
  negligible and its eager codec initialization lacks a pure boundary.

## References

- PR #246: Create constructor/family/per-entry attribution.
- PR #255: Create `AllPackets` no-go.
- Create `AllBlockEntityTypes` and `CreateBlockEntityBuilder` at source pin
  `ac0c444d9828da3453ae8cc65338e8de063286fb`.
- Registrate `BlockEntityBuilder` and `OneTimeEventReceiver` at
  `fd0f4e7fa1e53090cbe2024483b82743366d4c87`.

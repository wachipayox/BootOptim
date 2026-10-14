# Xaero World Map startup update-check skip

Status: **production, default enabled** (promoted 2026-10-14 at explicit user
decision; physical runtime validation of the first world and first map use was
waived as low-risk). Research and evidence:
[`archive/research/xaero-startup-update-check-2026-09-25.md`](../../archive/research/xaero-startup-update-check-2026-09-25.md),
original PR #292.

## Problem

Xaero World Map 1.41.0 calls `xaero.map.misc.Internet.checkModVersion()` from
`WorldMap.loadLater()` on the render thread. It is a blocking URL request with
900 ms connect/read timeouts. A physical-PC run attributed a 1.653 s first-menu
frame gap to it (NeoForge reported the deferred task at 1.425 s). Turning off
Xaero's update notifications does not avoid it: the option is read after the
request starts.

## Mechanism

`XaeroWorldMapUpdateCheckMixin` (`@Pseudo`, optional MixinExtras
`WrapOperation`, `require = 0`) omits only that `checkModVersion()` call inside
`loadLater()`. Patreon checking, map initialization and every other Xaero
Internet function are unchanged. It logs
`BOOTOPTIM_XAERO_UPDATE_CHECK status=skipped version=1.41.0`.

## Safety

- Not pinned to a Xaero version (policy 2026-10-14). If `loadLater()` no longer
  calls `checkModVersion()`, the injection finds no target, stock behaviour
  remains and the post-apply check logs `[WBOMBO-COMPAT] FALLBACK`.
- Absent Xaero: the pseudo-mixin does not apply (fail open, no hard dependency).
- Kill switch: `-Dboot_optim.xaeroSkipStartupUpdateCheck=false`.
- Observable trade-off: no automatic Xaero World Map update notification for
  this version.

## Evidence and claim

Hosted exact-pack smoke `36154711895` reached the main menu with zero
BootOptim/Mixin errors, the skip marker present and the old version-check error
absent. This removes a visible render-thread network stall around the first
menu; **no time-to-main-menu improvement is claimed**.

## Reopen / revisit

On a `[WBOMBO-COMPAT] FALLBACK` for this mechanism, on any
unexpected map behaviour, or if physical evidence shows the stall no longer
occurs.

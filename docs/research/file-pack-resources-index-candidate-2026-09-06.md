# FilePackResources enumeration index candidate — 2026-09-06

## Status

Candidate only; disabled by default and not promoted. The kill-switch is
`-Dboot_optim.filePackResourcesIndex=true`.

## Premise

The physical diagnostics found 1.321–5.276 seconds of inclusive ZIP
enumeration on the target laptop, concentrated in `Glowing Trim Armors`,
while hosted smoke measured about 0.793 seconds. The same `ZipFile.entries()`
central-directory traversal is repeated for every `getNamespaces` and
`listResources` call. The candidate snapshots the entries once per open
`ZipFile`, then returns a fresh enumeration over that snapshot.

## Safety invariants

- The feature is opt-in and fail-open when disabled.
- Entry order and duplicate entries are preserved exactly by the immutable
  list; vanilla namespace/path filtering and warning behavior still run.
- No `ResourceOutput`, `IoSupplier`, pack precedence, ZIP open/close, or
  reload scheduling behavior is changed.
- The cache is keyed weakly by the actual open `ZipFile`; a closed/reopened
  pack receives a new key, and no strong global pack/file key is retained.
- The mixin is client-only, targets only `FilePackResources`, and does not
  overlap ModernFix's inactive GA `FilePackResourcesMixin`.

## Decision gate

Run exact-pack hosted A/B with the property true/false and require identical
resource selection, atlas dimensions, menu completion, zero BootOptim Mixin
errors and no semantic/visual regression. A laptop run is justified only if
the hosted candidate has a coherent reload-to-menu win; otherwise close it as
another inclusive-only optimization.

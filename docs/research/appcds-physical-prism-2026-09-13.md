# AppCDS dynamic archive on the physical Prism laptop — 2026-09-13

**Status:** LIMITED / not a production candidate yet.

## Question

Can a dynamic AppCDS archive reduce time to the main menu for the exact pack on
the old Windows/HDD laptop, when launched by Prism with the production
bootstrap JAR?

## Contract and validation

- JDK: Oracle Java `21.0.9+7-LTS-338`.
- Candidate wrapper SHA-256:
  `90456540C732C662B07E0D78F035A77D3FFEED3FEDF8507DD50B9DF29DE0A128`.
- Training used `-Xshare:auto` plus `-XX:ArchiveClassesAtExit`; consumption
  used `-Xshare:auto`, `-XX:+VerifySharedSpaces` and the exact archive path.
- Every run used the transaction runner, one staged early-service wrapper,
  Prism-owned memory fields, and command-line token validation after Java
  appeared.  Each run reached `main_menu` and auto-closed; original config and
  JAR hashes were restored after every run.

Training produced a 286,457,856-byte dynamic archive.  The JVM recorded 35,542
shareable classes; its AppCDS dump took about 27 seconds after normal game exit,
outside the menu metric.  Every candidate log explicitly opened the dynamic
archive and mapped dynamic regions 0, 1 and 2.  This is a real physical mapping,
not `-Xshare:auto` silently falling back.

## Physical paired results

`main_menu` is BootOptim uptime from the ModLauncher marker, not launcher or
Prism preparation time.

| Pair order | Control (ms) | AppCDS (ms) | AppCDS minus control |
|---|---:|---:|---:|
| control → candidate | 398343 | 401671 | +3328 ms |
| control → candidate | 390479 | 356993 | -33486 ms |
| control → candidate | 361270 | 365528 | +4258 ms |
| candidate → control | 397836 | 417443 | +19607 ms |

The all-control/all-candidate medians would misleadingly suggest a candidate
win because the first three pairs were ordered control then candidate.  The
per-pair deltas, including the reversed pair, have median **+3793 ms** for
AppCDS.  The only large candidate win did not repeat.  The full dynamic archive
therefore has no demonstrated time-to-menu benefit on this physical HDD laptop.

## Important harness correction discovered during setup

The first attempts were invalid before Java or its intended JVM options:

1. After a Windows reboot Prism may spend more than five minutes refreshing
   metadata/auth/assets before creating Java; the pre-Java appearance grace is
   now 180–600 seconds and separate from Java's timeout.
2. `instance.cfg` `JvmArgs` must be written as one outer-quoted QSettings value.
   An unquoted payload could remain visibly present yet Prism omitted every
   custom JVM argument.  The runner's effective process-token check rejected
   it.  File contents alone are never evidence of an effective JVM contract.

These tooling changes are in `codex/appcds-physical` commit `cd8b6b3`.

## Reopening criteria

Do not ship the 286 MB generic archive.  Reopen only with a materially changed
premise: a much smaller archive targeted by critical-path class-load evidence,
or proof that a different archive boundary avoids the HDD mapping cost while
retaining the class-load work it removes.  Any reopening needs order-balanced
physical pairs, explicit archive-map proof, and the same menu endpoint.

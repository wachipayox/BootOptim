# ModernFix reload-parallelism lease — physical evidence (2026-09-06)

Status: **HOSTED-SURVIVING / PHYSICAL REPLICATION INCONCLUSIVE, NOT PROMOTED**

PR #126 temporarily lowers the exact ModernFix 5.27.14 resource-reload executor target from 3 to 2 only during the first client reload, then restores the exact previous target. The mechanism is default-off and fail-open.

## Same-artifact laptop pair

The final Windows laptop was kept logged in and was not rebooted. Both runs below used the same PR #126 artifact (SHA-256 `2FC39736775C37F2E0479E5203B6454188D6704DFF218FD9585415F4613184DD`), the same exact-pack instance, and the same JVM arguments; only the lease property differed. Both reached the main menu and exited normally.

| run | lease | main menu | mod entrypoint | resource reload start → FancyMenu finished |
| --- | --- | ---: | ---: | ---: |
| `modernfix-lease-physical-025` | enabled | **427.170 s** | **104.476 s** | **231.206 s** (`03:24:54.148` → `03:28:45.354`) |
| `modernfix-lease-physical-027` | disabled | **535.912 s** | **193.159 s** | **254.963 s** (`03:50:32.024` → `03:54:46.987`) |

The enabled run logged `applied=true restored=true outcome=success previous_parallelism=3 target_parallelism=2 after_restore=3`. The direct post-entrypoint boundary improved by about **20.1 s**, and the resource-reload-to-FancyMenu interval by about **23.8 s**. The total delta was **−108.7 s**, but **88.7 s of that difference is before mod entrypoint** and is therefore not attributable to the lease. Native MCEF/download and other page-cache state remained variable even with the same artifact; the pair was not a cold-cache/reboot-controlled experiment.

## Reversed-order replication

To test whether the first pair's direction survived ordering, the same artifact and exact-pack instance were run again without rebooting, with the control first and the lease second:

| run | lease | main menu | mod entrypoint | resource reload start → FancyMenu finished |
| --- | --- | ---: | ---: | ---: |
| `modernfix-lease-physical-028` | disabled | **444.332 s** | **154.618 s** | **218.405 s** (`04:02:25.178` → `04:06:03.583`) |
| `modernfix-lease-physical-029` | enabled | **676.432 s** | **365.481 s** | **216.863 s** (`04:14:52.561` → `04:18:29.424`) |

The lease applied and restored successfully in run 029 (`previous_parallelism=3`, `target_parallelism=2`, `after_restore=3`). The reload interval changed by only **−1.542 s**, while the candidate was **+232.100 s** slower overall; **+210.863 s** of that difference was already present before the mod-entrypoint boundary. Thus the reversed pair does not reproduce the earlier reload/post-entrypoint movement. Both runs reached the main menu and the original JAR/config were restored afterward.

## Decision

The first pair remains a useful hypothesis signal, but the reversed-order pair fails to reproduce it. The hardware-sensitive candidate therefore stays **default-off and unpromoted**; current evidence is insufficient to claim a real reload or end-to-end win. Do not spend more laptop boots on this exact lease unless a new controlled design removes the native/cache confounders (or a materially stronger hosted/physical replication makes the effect coherent). The implementation's fail-open/restoration behavior is still covered by the hosted tests and the two physical lease markers.

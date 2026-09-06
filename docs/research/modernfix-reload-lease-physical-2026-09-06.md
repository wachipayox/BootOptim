# ModernFix reload-parallelism lease — physical evidence (2026-09-06)

Status: **HOSTED-SURVIVING / PHYSICAL SIGNAL, NOT PROMOTED**

PR #126 temporarily lowers the exact ModernFix 5.27.14 resource-reload executor target from 3 to 2 only during the first client reload, then restores the exact previous target. The mechanism is default-off and fail-open.

## Same-artifact laptop pair

The final Windows laptop was kept logged in and was not rebooted. Both runs below used the same PR #126 artifact (SHA-256 `2FC39736775C37F2E0479E5203B6454188D6704DFF218FD9585415F4613184DD`), the same exact-pack instance, and the same JVM arguments; only the lease property differed. Both reached the main menu and exited normally.

| run | lease | main menu | mod entrypoint | resource reload start → FancyMenu finished |
| --- | --- | ---: | ---: | ---: |
| `modernfix-lease-physical-025` | enabled | **427.170 s** | **104.476 s** | **231.206 s** (`03:24:54.148` → `03:28:45.354`) |
| `modernfix-lease-physical-027` | disabled | **535.912 s** | **193.159 s** | **254.963 s** (`03:50:32.024` → `03:54:46.987`) |

The enabled run logged `applied=true restored=true outcome=success previous_parallelism=3 target_parallelism=2 after_restore=3`. The direct post-entrypoint boundary improved by about **20.1 s**, and the resource-reload-to-FancyMenu interval by about **23.8 s**. The total delta was **−108.7 s**, but **88.7 s of that difference is before mod entrypoint** and is therefore not attributable to the lease. Native MCEF/download and other page-cache state remained variable even with the same artifact; the pair was not a cold-cache/reboot-controlled experiment.

## Decision

This is the first physical signal that the hosted `−242 ms` reload median may understate the benefit on the 2C/4T software-renderer laptop. It is not sufficient to enable the lease globally: the pair is a single no-reboot physical comparison, the pre-entrypoint clock remains noisy, and the candidate changes resource scheduling on a constrained machine. Keep the candidate default-off and require a second reversed-order same-artifact pair or stronger hosted/physical replication before promotion.


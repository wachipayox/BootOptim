# MCEF first-consumer defer — laptop gate 2026-09-04

Status: **PRELIMINARY / DO NOT PROMOTE FROM THIS PAIR**

The Windows 10 i3-2350M / 8 GiB laptop was reached through a LAN-only,
interactive-session Prism benchmark copy. The user’s live profile was not
modified. Java was Oracle 25.0.4 with the pack’s 6 GiB G1 configuration.

## Mechanism check

The PR #90 bootstrap at `e348ef8` was installed only in the isolated copy.
With its default property, `latest.log` recorded:

```text
BOOTOPTIM_MCEF_FIRST_CONSUMER status=deferred mcef_version=2.1.6-1.21.1
```

The candidate reached BootOptim’s main-menu marker, so this is a real active
mechanism check, not a feature-off or Mixin-load false positive.

## Measurements

All values are raw Java-uptime `main_menu` markers, without a Windows reboot:

| Run | Build / mode | Main menu |
| --- | --- | ---: |
| warm-baseline-005 | integration `ad39b13` | 504.588 s |
| warm-baseline-006 | integration `ad39b13` | 520.401 s |
| mcef-candidate-007 | PR #90 default-on | 619.160 s |
| mcef-control-008 | same PR #90 JAR, `-Dboot_optim.mcefFirstConsumerDefer=false` | 755.591 s |

The immediate same-JAR pair favors defer by **136.431 s**, but this is not a
ship decision. The candidate was earlier in a long no-reboot series and was
already slower than both integration warm runs; the following control was much
slower still. Thermal state, page cache, background activity, and MCEF native
state are therefore not bounded by one sequential pair.

## Decision and reopening protocol

Keep PR #90 as the already-hosted-validated promotion candidate. This physical
run confirms its Windows mechanism reaches the menu with MCEF deferred, but it
does not quantify a trustworthy laptop saving. Before promotion, collect an
alternating candidate/control/candidate sequence after an explicit cooldown and
record CPU temperature/background state where available. Use the same PR #90
JAR and only its kill switch; do not repeat the rejected reload-overlap premise
from PR #78. A post-menu real MCEF consumer smoke remains required for final
Windows visual/lifecycle assurance.

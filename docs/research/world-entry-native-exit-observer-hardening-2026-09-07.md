# World-entry native-exit observer hardening — 2026-09-07

Status: **DIAGNOSTIC ONLY / SIN EVIDENCIA FÍSICA**.

This note is a narrow follow-up to `world-entry-native-termination-2026-09-07.md`. It does not change MCEF, CEF, audio, GPU, rendering, gameplay, or startup policy.

## Why this follow-up exists

The first version of PR #169 could classify the last MCEF/JCEF bootstrap marker, Java/CEF-helper exit codes, and Windows Application Error/WER 1000/1001 records. Two forensic ambiguities remained:

1. a clean-looking parent exit code did not tell us whether Minecraft explicitly requested shutdown or whether JVM shutdown machinery actually ran;
2. Windows may publish Application Error/WER records shortly after the Java process has already disappeared, so querying immediately at parent exit can miss the event we are trying to capture.

## Opt-in orderly-stop and JVM-shutdown markers

When `-Dboot_optim.mcefNativeBootstrapProbe=true` is enabled on the client, BootOptim registers a JVM shutdown hook at mod construction time and emits:

`BOOTOPTIM_NATIVE_EXIT_PROBE stage=installed ...`

The same opt-in also marks an explicit Minecraft client stop request at `Minecraft.stop()` HEAD:

`BOOTOPTIM_NATIVE_EXIT_PROBE stage=minecraft_stop ...`

If JVM shutdown later reaches registered shutdown hooks, the hook emits through both `System.err` and the logger:

`BOOTOPTIM_NATIVE_EXIT_PROBE stage=jvm_shutdown_hook ...`

The property remains default-off. These markers do not alter MCEF/audio/GPU behavior and do not make the diagnostic run valid for performance claims.

Interpretation is deliberately asymmetric:

- **`minecraft_stop` present**: strong evidence that Minecraft itself requested an orderly client stop before process disappearance;
- **`jvm_shutdown_hook` present**: strong evidence that JVM shutdown machinery ran;
- **both absent + fatal NTSTATUS/WER**: consistent with abrupt/native termination;
- **both absent + clean-looking exit/no WER**: still ambiguous. External termination, launcher/task cleanup, fatal VM failure, or loss of final output can bypass or hide the markers. Do not label it graceful solely from exit code 0.

Hosted exact-pack revealed why both markers are useful: the benchmark's `exitOnTitle` path calls `Minecraft.stop()`, but the final shutdown hook marker is not guaranteed to survive/appear in the captured output. `minecraft_stop` therefore provides the earlier, more reliable Java-level orderly-stop boundary.

## Post-exit WER grace

`remote_laptop_native_exit_probe.ps1` records the actual parent `exitedUtc` immediately when the Java process ends, then waits a default 3000 ms before querying Application events. The wait is configurable with:

`-PostExitEventGraceMilliseconds <0..10000>`

The JSON schema is now `2` and records both `postExitEventGraceMilliseconds` and `eventQueryCompletedUtc`. The wait is outside the process lifetime and must not be added to startup/runtime timing.

## Physical classification rule

For the single next laptop run, classify using all four evidence families together:

1. ordered MCEF/JCEF in-process markers plus `minecraft_stop` / `jvm_shutdown_hook` lifecycle markers;
2. Java parent exit code in signed + exact 32-bit hex form;
3. observed direct CEF-helper identity/type and exit code when available;
4. WER/Application Error 1000/1001 fault module and exception code collected after the post-exit grace.

No one family overrides a concrete faulting module/NTSTATUS. In particular, a nearby CEF marker does not supersede an OpenAL/soft_oal WER, and an exit code that appears clean does not prove orderly shutdown without lifecycle evidence.

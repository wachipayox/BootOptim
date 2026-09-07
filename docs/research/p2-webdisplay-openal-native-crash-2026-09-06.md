# P2 WebDisplays/MCEF physical run — native OpenAL crash — 2026-09-06

Status: **DIAGNOSTIC / NOT ATTRIBUTED TO BOOTOPTIM**

## Run

Interactive exact-pack laptop run `p2-webdisplay-20260906b` used the production
BootOptim artifact (`70744A8B733C1FE16D56C854AE34E4ED5A6B356EF2F06266F3980908080FDB01`),
the current FancyMenu fork (`A3284A4A3C2A947C5CFF0B1F2E3B30B51D1C893674F79338074261873D80484`),
`mcefFirstConsumerDefer=true`, and `exitOnTitle=false`. The run reached the main
menu and then entered the world; the runner marked it completed only because the
Java process exited.

## Evidence

- `latest.log` ends abruptly at `17:28:58.383` immediately after the FancyMenu
  local-world handshake. It contains no `Stopping!`, save, shutdown, or Java
  crash-report footer.
- `debug.log` ends at `17:29:03.685` while the Render thread is still applying
  ordinary mixins. There is no Java exception corresponding to process exit.
- No new file appeared in the instance `crash-reports` directory.
- Windows Application Error event **1000** at `17:29:07` identifies `javaw.exe`
  as crashing in `OpenAL.dll` from LWJGL:

  ```text
  Exception: 0xc0000409
  Module: C:\Users\wachi\AppData\Local\Temp\lwjgl_wachi\3.3.3+5\x64\OpenAL.dll
  Offset: 0x00000000000a2b05
  ```

  Windows Error Reporting event **1001** classifies it as `BEX64` and records
  the same report ID. This is a native process termination, so Minecraft never
  got the opportunity to emit its normal crash report.

- The laptop already had many earlier `javaw.exe` crashes with the identical
  `OpenAL.dll` version, exception code, and offset on the same day (12:15,
  12:28, 13:05, 13:29, 13:40, 13:53, 14:27, and others). That recurrence is
  strong evidence of a pre-existing laptop/JVM/OpenAL failure mode rather than
  a new BootOptim Java exception.

## Relevant timing and interpretation

The world join marker appeared at `17:28:23.543`. MCEF then repeatedly logged
that it was attempting to load (`17:28:50.340`, `17:28:58.405`, `17:28:59.409`)
while the client was entering/rendering the world. The faulting native module is
nevertheless OpenAL, not MCEF/CEF, BootOptim, or FancyMenu. The temporal overlap
means MCEF/WebDisplays may be an interaction trigger worth isolating, but this
run does not prove causality.

JEI/BuildersDelight errors and Sable unknown-block errors also appear in the log,
but the client continued past them and reached the world; they are not the
termination evidence.

## Disposition and reopening gate

Do not classify this as a BootOptim regression or as a normal Java crash. Treat
it as a native audio/runtime instability that invalidates this interactive P2
run. Reopen only with an isolation pair on the same laptop (for example, the
same pack with audio/OpenAL disabled or without MCEF/WebDisplays) and capture
Windows event 1000/1001 again. Until then, do not use this run for MCEF startup
performance or WebDisplays gameplay claims.

Artifacts: `bootstrap/logs/laptop-p2-webdisplay-20260906b/latest.log`,
`debug.log`, and `result.json`.

## Follow-up imported-world run and native isolation — 2026-09-06

The user repeated the test with a world imported from another PC that was
already generated. The client again reached `Loading terrain`/world entry and
closed without a user-requested exit. This rules out world creation as the
specific trigger; the relevant boundary is the client-to-world transition and
the subsystems initialized there.

The second run reached the following milestones before the native termination:

- OpenAL initialized successfully at `18:49:49.970` on the laptop's Realtek
  device and the sound engine started at `18:49:49.974`.
- The player joined the imported world at `18:55:46.958`; BootOptim recorded
  `joined the game` at `18:55:52.914`.
- MCEF repeatedly logged an attempt to load at `18:56:28.015`, `18:56:29.386`,
  `18:56:34.398`, and `18:56:35.403`. No CEF-initialized marker was present
  before termination, so this is temporal overlap, not proof that MCEF caused
  the failure.
- Windows Application Error 1000 at `18:56:49` again identified `javaw.exe`
  faulting in `OpenAL.dll` 1.23.1.0 at offset `0xA2B05`, exception
  `0xC0000409`; WER event 1001 classified the same failure as `BEX64`.

The faulting DLL was copied from the laptop and hashed as
`0630aa42492f78126da1c4d99769632e03c55b2e63b050efe7e3005e07e3e6fc`. It is
byte-identical to the official LWJGL 3.3.3 `lwjgl-openal` Windows x64 native,
so the evidence currently points to an OpenAL Soft/WASAPI runtime failure on
this machine rather than a modified or corrupt pack binary.

### Isolation currently running

At `19:06:53` a separate scheduled launch started the same exact pack and
single production BootOptim JAR with the per-process environment override
`ALSOFT_DRIVERS=null`. This disables the hardware OpenAL backend only for this
test process; it does not change Windows, Java, drivers, or the user's normal
configuration. The Java process is currently alive in the interactive session,
so logs must not be read until it exits. The result gate is:

1. If the imported world survives with the null backend, the WASAPI/audio
   backend is a necessary part of the crash path; follow-up tests will compare
   `dsound`/other OpenAL backends and then evaluate a safe, opt-in game-side
   mitigation.
2. If it still crashes with the same OpenAL signature, OpenAL is likely the
   termination site of a broader native race (possibly MCEF/GL/audio
   concurrency), and the next pair will disable MCEF/WebDisplays while keeping
   normal audio.

This document intentionally records the native event and the isolation plan,
not a promotion decision. No BootOptim production code has been changed based
on this crash yet.

### First null-backend attempt: menu-only, not a causal result

The `ALSOFT_DRIVERS=null` process reached the main menu at `19:14:19.447`
(`BOOTOPTIM_STARTUP ... main_menu uptime_ms=446037`) and exited without a new
Windows Application Error 1000/1001. Its log contains no `joined the game` or
`Loading terrain` marker; the automated key sequence therefore did not enter
the imported world. This is useful as a startup sanity check, but it is **not**
the promised OpenAL isolation result for the crash boundary. The next null
backend run must use a deterministic world-selection action or a longer
post-menu wait before the second activation key, then remain in the world long
enough to cross the previously observed one-minute failure window.

### Deterministic world launch method

The initial key-driven attempt was replaced after checking the real launcher
implementation and upstream documentation. Prism Launcher exposes `--world`
for an instance launch; its current source converts that target into
Minecraft's `--quickPlaySingleplayer` argument when the profile advertises the
Quick Play feature. Mojang's Java 1.20 technical notes define that argument as
the **world save-folder name**, not the display title. The valid invocation for
this fixture is therefore Prism `--launch <instance> --world "realmente lo
hizo_"`, where `realmente lo hizo_` is the actual directory under `saves/`.

Sources checked: Prism's `MinecraftInstance::processMinecraftArgs`
(`https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/MinecraftInstance.cpp`)
and Mojang's Java 1.20 Quick Play specification
(`https://feedback.minecraft.net/hc/en-us/articles/16499677456781-Minecraft-Java-Edition-1-20-Trails-Tales`).
The current null-backend run uses this direct path rather than synthetic GUI
keys.

The same Mojang specification documents `--quickPlayPath`, which writes a
join record containing the singleplayer folder identifier and world metadata.
It is a better machine-readable harness boundary than waiting for a window or
simulated keypress; a future direct-world run can add, for example,
`--quickPlayPath quickPlay/log.json` and use the resulting `lastPlayedTime` as
the entry marker.

### Prism environment correction and second null attempt

The first direct-world launch did pass `--quickPlaySingleplayer realmente lo
hizo_`, but its parent-process `ALSOFT_DRIVERS=null` did **not** reach Java:
Prism intentionally rebuilds a clean launch environment and only re-adds
variables from its instance `Env` setting. The log consequently still said
`OpenAL Soft on Altavoces (Realtek High Definition Audio)`, so that run was a
normal-audio run and not an OpenAL isolation.

For the next attempt, a byte-for-byte backup of `instance.cfg` was made and the
temporary instance setting `Env={"ALSOFT_DRIVERS":"null"}` was added. This is
the supported Prism per-instance environment path and will be restored after
the run. The process was then launched with the direct world target. It was
stopped before reaching the late resource/sound phase (no OpenAL line and no
world-entry marker yet), so it is also **incomplete**, not evidence for or
against the null backend. The valid run must be allowed through the roughly
eight-minute laptop startup and the subsequent world-entry window.

### Valid null-backend isolation after restarting Prism — 2026-09-06

The previous attempt still used the already-running Prism process, which had
cached the instance before the temporary `Env` edit. Its OpenAL line still
named the Realtek device, so it was not an isolation run. For this attempt
Prism was closed, the instance was started from a fresh launcher process, and
the temporary instance settings were loaded with `OverrideEnv=true` and
`Env={"ALSOFT_DRIVERS":"null"}`. The file was restored after the run.

This execution passed the verified direct-world argument
`--quickPlaySingleplayer realmente lo hizo_`. Its milestones were:

- process launch at 20:09:32;
- `BOOTOPTIM_STARTUP phase=mod_entrypoint` at 20:11:29;
- OpenAL initialized on **`No Output`** at 20:15:14 (not the Realtek/WASAPI
  device);
- `BootOptimBench joined the game` at 20:20:24;
- the test process was stopped manually after the world-entry window, without
  a new Windows Application Error 1000/1001 after 20:09.

This is the first valid causal isolation. The imported world and the complete
MCEF/WebDisplays pack can reach the world with OpenAL's null backend, while the
normal Realtek backend repeatedly faults in `OpenAL.dll` at the same BEX64
offset. It strongly implicates the laptop's WASAPI/Realtek OpenAL backend (or
its interaction with the rest of the client), not world creation and not every
OpenAL path. It does not yet prove that WASAPI alone is sufficient: the next
pair should use the `dsound` OpenAL backend with normal audio, then compare a
new official OpenAL Soft binary if needed. No production BootOptim code or
user configuration was changed by this test.

### Fresh `dsound` backend run — 2026-09-06

After restoring the baseline, a second fresh Prism process was launched with
the same direct-world target and temporary per-instance
`Env={"ALSOFT_DRIVERS":"dsound"}`. The OpenAL log still names the Realtek
device; that is the logical device name and does not, by itself, identify the
OpenAL Soft backend. The run reached `Sound engine started` at 20:44:21 and
`BootOptimBench joined the game` at 20:50:02. No Application Error 1000/1001
was recorded from the 20:37 launch through the world-entry window. The process
then exited without an OpenAL/WER termination record; the temporary config was
restored immediately afterwards.

This is encouraging evidence that selecting a non-WASAPI OpenAL backend can
avoid the observed native failure while retaining normal audio, but it is not
yet a promotion: the backend name is not logged explicitly and this was one
physical run. A follow-up should add an explicit backend probe or repeat the
same `dsound`/normal-device pair before designing a fail-open mitigation.

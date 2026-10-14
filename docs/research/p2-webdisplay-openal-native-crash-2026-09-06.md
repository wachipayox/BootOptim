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

## Reproduction on the later P0 world-entry run — 2026-09-07

The subsequent P0 run reproduced the same failure while entering the imported
world. Windows event 1000 at `13:13:36` and WER event 1001 at `13:13:47` again
reported `javaw.exe` 25.0.4.0 terminating in
`%LOCALAPPDATA%\\Temp\\lwjgl_wachi\\3.3.3+5\\x64\\OpenAL.dll`, version 1.23.1.0,
with BEX64 / `0xc0000409` at offset `0xA2B05`. The binary hash was unchanged
(`0630AA42492F78126DA1C4D99769632E03C55B2E63B050EFE7E3005E07E3E6FC`). A
review of the rotated debug log shows normal OpenAL initialization on the
Realtek device and `Sound engine started` at 13:08:25, zero active AnalogAudio
streams at its 13:13:06 cleanup, and integrated-server startup at 13:13:13;
the Java log ends during ordinary Sable/chunk setup without an exception or
Minecraft crash report. The same WER signature was present in many earlier
`javaw.exe` reports on this laptop.

This strengthens the conclusion that the termination is a recurring native
OpenAL Soft 1.23.1 / laptop audio-backend failure. World creation is ruled out,
and BootOptim is not implicated by a Java stack or a BootOptim Mixin error.
MCEF/WebDisplays overlap the timing and remain a possible trigger, but there
is no causal evidence yet. The earlier valid `ALSOFT_DRIVERS=null` run reached
the world with `No Output`, and the temporary `dsound` run crossed the same
world-entry window without a WER event; those are isolation evidence, not a
production decision. The next safe step is an explicit backend probe/repeat,
then an opt-in fail-open mitigation only if it preserves acceptable audio
semantics. Do not replace the bundled DLL or alter Windows/driver settings as
part of BootOptim until that matrix is complete.

### Fresh `dsound` run kept open for WebDisplays validation — 2026-09-07

After the P1 diagnostic run was collected and the baseline wrapper was
restored, a fresh Prism process was launched directly into the imported world
with only a temporary per-instance environment override:

```text
OverrideEnv=true
Env={ALSOFT_DRIVERS:dsound, ALSOFT_LOGLEVEL:3,
     ALSOFT_LOGFILE:<instance>/.minecraft/logs/openal-dsound.log}
```

The run uses the production BootOptim wrapper, `exitOnTitle=false`, and the
same world target as the previous isolation runs. OpenAL initialized on the
Realtek logical device, the integrated server started, and spawn preparation
reached 95% with `Time elapsed: 43069 ms`. The process remained responsive and
no new Windows Application Error 1000/1001 appeared. The transition then
continued in WorldEdit's server-start hook: its complete block-state maps are
being generated synchronously (for example, `computercraft:cable` took
30.346 s). This explains why the loading screen is still visible and is not a
new audio crash. OpenAL Soft did not create the requested diagnostic logfile,
so the logical device name alone does not prove the selected backend. Keep the
instance override only for the user's WebDisplays test; restore the
byte-for-byte baseline afterwards. This is strong laptop-only mitigation
evidence, not yet a user-facing BootOptim default.

### C2ME/chunk-scheduler isolation and post-entry native crash — 2026-09-07

To separate the WorldEdit delay from the earlier world-entry failure, the
WorldEdit and SableEdit jars were temporarily staged out together (removing
only WorldEdit first is invalid because SableEdit has a hard WorldEdit class
dependency). The run then reached the integrated-server constructor but
blocked in `ChunkTaskPriorityQueueSorter.getProcessor`, with the server thread
waiting on a `CompletableFuture.join` and a C2ME worker waiting in FlowSched.
The documented C2ME property
`-Dc2me.disableModule.opts.scheduling=true` did not remove that base scheduler
wait, so the main C2ME jar was temporarily staged out as a decisive isolation.

With C2ME, WorldEdit, and SableEdit all absent, and the temporary per-instance
`ALSOFT_DRIVERS=dsound` override still active, the imported world crossed spawn
preparation (`Time elapsed: 49887 ms`) and recorded
`Wachipayoxx joined the game` at 14:41:28. MCEF initialized and the client
stayed alive briefly, but Windows Application Error 1000 then recorded at
14:42:13 the same native failure as the earlier P0: `javaw.exe` 25.0.4.0,
`OpenAL.dll` 1.23.1.0, exception `0xc0000409`, offset `0xA2B05`. The user saw
one frame of the world immediately before the close. The run therefore proves
that removing C2ME fixes the scheduler deadlock but does not fix the native
OpenAL failure; the `dsound` override was either not effective or does not
avoid this 1.23.1 fault on this machine. The known `ALSOFT_DRIVERS=null`
profile remains a no-audio safety fallback, while `winmm` and an explicit
backend probe are the next configuration experiments before any default is
chosen. None of the temporary jar removals is a production decision.

### Official OpenAL Soft replacement and AnalogAudio isolation — 2026-09-07

The next two runs used the official OpenAL Soft 1.23.1 `soft_oal.dll` from
the upstream Windows x64 release, selected through LWJGL's
`-Dorg.lwjgl.openal.libname` property. The process module list confirmed the
replacement was actually loaded from the per-instance diagnostic directory.
The run reached `Sound engine started`, completed world spawn, initialized
MCEF/Chromium, and then Windows event 1000 reported `soft_oal.dll` 1.23.1,
exception `0x40000015`, at offset `0xD9076`. This is a different fail-fast
signature but the same post-MCEF timing, so merely swapping the bundled DLL
does not solve the problem.

AnalogAudio was then staged out while keeping the same explicit OpenAL
replacement, world, C2ME/WorldEdit/SableEdit isolation, and MCEF deferral.
The JAR was restored after the run. The no-AnalogAudio run again reached
`Chromium Embedded Framework initialized` (15:26:41) and FancyMenu's MCEF
handlers, then Windows reported the same `soft_oal.dll` `0x40000015` failure
at 15:26:50. AnalogAudio is therefore not the primary cause of this crash;
its presence may still affect audio semantics, but removing it does not move
the failure boundary.

### Standalone LWJGL/OpenAL harness — 2026-09-07

To avoid paying the six-to-eight minute modpack startup for every audio
hypothesis, an ignored diagnostic harness was compiled under
`.agent-coordination/openal-harness/`. It uses the exact laptop LWJGL 3.3.3
core/OpenAL jars and can select either the stock extracted `OpenAL.dll` or the
official `soft_oal.dll`. It opens the device/context, creates and destroys
sources and buffers, plays short PCM buffers, checks errors, and repeats the
cycle from one or four Java threads. The first four-thread pass was later
found to use a context from the wrong thread, so it is retained only as
historical evidence. The corrected context-owning version is recorded below.
The single-thread stock/official-Soft path still does not reproduce the crash;
the remaining boundary is the interaction with the full client, especially
the MCEF/WebDisplays transition and the render/audio lifecycle, not a basic
`alcOpenDevice` or source/buffer failure.

### Controlled context-isolation rerun — 2026-09-07

AnalogAudio was verified restored in the laptop instance before this test
(`AnalogAudio -160- {v0.1.0} [1.21.1] [SYNC].jar`, 2,029,363 bytes). The
ignored harness was tightened so every worker owns its own OpenAL device and
thread-local context, matching the OpenAL ownership rule instead of issuing
AL calls from a context-less worker. A short matrix was then run with the
exact laptop LWJGL 3.3.3 jars:

| library | contexts/threads | result |
|---|---:|---|
| bundled stock `OpenAL.dll` | 1 | pass, 100 cycles |
| official `soft_oal.dll` 1.23.1 | 1 | pass, 100 cycles |
| bundled stock `OpenAL.dll` | 2 | pass, 25 cycles |
| official `soft_oal.dll` 1.23.1 | 2 | Java-side `AL_INVALID_OPERATION` at a late `alSourceStop` |
| bundled stock `OpenAL.dll` | 4 | Java-side `AL_INVALID_OPERATION` at a late `alSourceStop` |
| official `soft_oal.dll` 1.23.1 | 4 | Java-side `AL_INVALID_OPERATION` during the first `alBufferData` |

No native process termination occurred in this short run. The multi-context
errors are a useful concurrency signal, but they are not yet a reproduction
of Minecraft's single SoundEngine context and must not be promoted as the
crash cause. The isolated test therefore still separates the recurring full
client/native boundary from basic OpenAL setup; the next diagnostic should
exercise Minecraft's actual sound lifecycle or identify a mod that opens or
touches OpenAL from another thread, rather than increasing the cycle count on
the laptop.

The laptop's active mod directory was left with AnalogAudio restored and only
one BootOptim runtime jar. The temporary C2ME/WorldEdit/SableEdit isolation
remains staged out until the world-entry diagnosis is complete.

### Laptop audio topology and driver candidate — 2026-09-07

Read-only inspection over the relay identified the machine as a **Fujitsu
LIFEBOOK AH531** running Windows 10 22H2 (build 19045). Its physical audio
codec is Realtek ALC269 (`HDAUDIO\\FUNC_01&VEN_10EC&DEV_0269&SUBSYS_10CF1000`)
with Realtek driver `6.0.1.7865`, dated 2016-06-28. Fujitsu's AH531 support
results list a newer matching Realtek package `6.0.1.7931` (2016-10-06), so a
vendor-driver update is a plausible, reversible diagnostic candidate, but it
has not been installed yet.

The laptop also exposes an Intel HDMI audio controller using Microsoft's
driver and an Intel Bluetooth adapter. A 3.5-mm headset would still use the
same Realtek codec and therefore is not an independent driver test. A USB
audio headset/DAC, a paired Bluetooth headset, or an HDMI display with audio
would bypass the Realtek endpoint and is the cleanest next hardware A/B. The
official vendor listing is [Fujitsu's AH531 support result](https://support.ts.fujitsu.com/IndexQuickSearchResult.asp?lng=EN&q=AH531);
third-party driver aggregators should not be used.

This does not yet prove the Realtek driver is the sole trigger: the stock and
official OpenAL DLL both fail only in the full client, while the isolated
single-context harness passes. It does make an alternate-output run and the
OEM driver update higher-value diagnostics than further long OpenAL loops.

### OEM audio-driver update — 2026-09-07

The official Fujitsu/Realtek self-extracting package `E1023174.exe` was
downloaded from the vendor site, verified at the published 316,342,547 bytes,
and installed silently with its InstallShield response file. After a forced
reboot, Windows reported the Realtek device at driver version **6.0.1.8004**
dated 2016-11-29. The post-reboot run below supersedes the temporary
`LogonUI`/"not yet tested" status recorded during installation.

### Post-update normal-audio world run — 2026-09-07

The first full-client run after the OEM update used the normal bundled OpenAL
path (no custom `org.lwjgl.openal.libname` and no `ALSOFT_DRIVERS` override),
with AnalogAudio active and C2ME/WorldEdit/SableEdit still isolated. The fresh
log reported:

```text
OpenAL initialized on device OpenAL Soft on Altavoces (Realtek High Definition Audio)
Sound engine started
```

The process remained responsive, started the integrated server, generated the
spawn region, and logged the player in at `(-83.39685756733485, 63.0,
87.53455903382952)`. It then reproduced the native failure at 16:45:17:

```text
javaw.exe -> OpenAL.dll 1.23.1.0, exception 0xc0000409, offset 0xA2B05
```

The WER path was the stock LWJGL extraction under `%LOCALAPPDATA%\\Temp\\lwjgl_wachi`,
not `soft_oal.dll`, and the crash occurred after MCEF finished initializing
and FancyMenu rebuilt its handlers. No new Realtek/HDAUDIO Windows error was
present. Updating the OEM driver therefore did **not** remove the laptop-only
full-client OpenAL crash; it remains correlated with the client/MCEF/audio
lifecycle rather than with the Realtek device initialization itself.

For subsequent laptop performance measurements, the instance is intentionally
set to `OverrideEnv=true` with `Env={ALSOFT_DRIVERS:null}`. This avoids the
known native audio crash on the non-consumer diagnostic laptop, but removes
audio and must not be interpreted as a final user-facing configuration.

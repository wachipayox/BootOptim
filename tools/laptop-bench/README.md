# Physical laptop benchmark tooling

Current runbook for the physical low-end laptop (2C/4T, HDD, 8 GB RAM, Windows 10). Rationale and the
failure classes these scripts prevent are in
[`docs/research/remote-laptop-operation-audit-2026-09-07.md`](../../docs/research/remote-laptop-operation-audit-2026-09-07.md).
Private connection details (LAN address, account) live only in the private wbombo workspace, never here.

## Which runner

| Runner | Launcher | Use |
| --- | --- | --- |
| `remote_laptop_pandora_run.ps1` | Wachiland Launcher (BootOptimPandora) `--run-instance-normal` | **Default since 2026-10-14.** Fast, deterministic preparation; JVM flags and Java binary come from the instance `info_v1.json`, which no launcher rewrites behind our back. |
| `remote_laptop_transaction.ps1` + `remote_laptop_interactive_run.ps1` | Prism `-l <instance>` | Legacy. On the HDD laptop Prism's pre-launch (metadata refresh, account refresh, Java check, asset downloads) took 10+ minutes and exceeded the 300 s launch grace; it can also rewrite `instance.cfg`. |

Both measure the same thing: BootOptim's startup report counts from JVM start, so the launcher does not
enter `mod_entrypoint`/`main_menu`. The launcher only matters for reliability and for not mutating the
measured configuration.

## Rules (do not regress)

- **Do not measure through Prism.** Prism is kept only as the owner of the instance's `.minecraft` directory
  (the launcher instance points at it through a junction). Never start a benchmark with `prismlauncher -l`.
- **Launch only through `remote_laptop_pandora_run.ps1`**, never by starting the launcher exe by hand or
  through an ad-hoc scheduled task: the runner is what checks preconditions, binds Java and restores state.
- **Before a campaign, run one primer** and confirm it reaches `finished` before trusting the driver loop.
- **If a run is invalid, stop and read the launcher log** (`%APPDATA%\WachilandLauncher\launcher.log`,
  rotated to `.old` on each start) and take a screenshot; do not loop.
- **Create measurement instances from the launcher UI** (it installs the loader and generates the patched
  NeoForge libraries), then point their `.minecraft` where needed. Copying only `info_v1.json` from another
  launcher's data directory skips the loader install; on 2026-10-14 that made the first launch fail with
  `Failed to launch ... IoError NotFound` until `libraries`/`assets`/`logconfigs` were copied over.
- **After updating the launcher exe**, check that its data directory did not change and that the instance,
  `libraries`, `assets`, `logconfigs`, `metadata` and the logged-in account are still there.

## Pandora transaction

`remote_laptop_pandora_run.ps1 -Action Stage|Run|Status|Postflight -RunId <id> ...`

1. **Stage** (launcher and target Java must be stopped; also checks that the launcher data directory has
   `libraries`/`assets`/`logconfigs` and that the instance `preferred_account` exists in `accounts.json`): backs up `info_v1.json` and the single live
   BootOptim wrapper, installs the candidate wrapper (hash-verified), writes `jvm_flags` and `jvm_binary`.
2. **Run**: registers an `Interactive`/`Limited` scheduled task in the logged-on session (with
   `-AllowStartIfOnBatteries -DontStopIfGoingOnBatteries`; without them Task Scheduler can refuse with
   `0x800710E0`) that runs `-Action Measure`.
3. **Measure** (inside the task): starts the launcher, binds the target Java by game directory, tokenizes its
   command line (`CommandLineToArgvW`), checks required/forbidden tokens and the Java executable, stores only a
   SHA-256 of the raw line, then blocks on the process. No log polling during the timed startup.
4. **Status**: reads `state.json` only. Do not poll during the first ~6 minutes of a run.
   It also tails the launcher log every ~10 s and fails fast on `Failed to launch` or `Auth Flow: Initial`
   (the launcher is waiting for an interactive login) instead of waiting out the 600 s grace.
5. **Postflight**: stops only the owned launcher/Java, restores the exact backups and verifies hashes and the
   one-wrapper invariant.

Send each call over SSH as a single `powershell -EncodedCommand` (UTF-16LE Base64). PowerShell errors come back
as CLIXML on stderr: decode them instead of filtering them away, or failures look like silent no-ops.

### Arms

Use identical flags except the variable under test, plus an inert marker property, for example
`-Dboot_optim.jitArm=control|candidate`, and list the marker as required in its own arm and forbidden in the
other. `-Dboot_optim.benchmark.exitOnTitle=true` makes the game exit at the title screen (it also enables the
lightweight profiler). `config/boot_optim.properties` must contain `startupLog=true`; delete
`logs/bootoptim-startup.log` before each run because the report appends.

Order: one discarded primer, then control, candidate, candidate, control. A campaign driver must **stop at the
first invalid run** and must be killable as a whole: killing only the parent shell of a Git Bash loop leaves
the loop running (2026-10-14: an orphaned loop started a Prism run on top of a Pandora run).

## Machine setup (done 2026-10-14, keep it that way)

- Power: AC sleep/hibernate/monitor timeouts 0; Wi-Fi adapter power management disabled (SSH dropped while
  the launcher saturated the 2.4 GHz link).
- Firewall: inbound ICMPv4 echo allowed, so reachability can be checked with ping.
- Java: Oracle JDK 25.0.4 is the measurement baseline (same as hosted exact-pack CI). The instance had been
  left pointing at JDK 21 by an older sweep; check `jvm_binary` before every campaign.
- Launcher: a fixed path (`C:\BootOptimBench\tooling\wachiland-launcher\WachilandLauncher.exe` plus
  `VERSION.txt`). Updating replaces only the exe; accounts/config live in `%APPDATA%\WachilandLauncher`
  and tokens in the Windows Credential Manager. A data-directory rename in the launcher (Pandora →
  Wachiland, 2026-10) silently leaves the instance behind: the instance and its `.minecraft` junction were
  re-created under the new directory.
- The account is logged in by the user; agents never type or copy credentials.
- Screen capture for diagnosis: an Interactive scheduled task running a `System.Drawing` `CopyFromScreen`
  script, then copy the PNG back.
- Old scheduled tasks from finished campaigns were removed; remove yours at the end of a campaign.

## Incident log 2026-10-14 (why the rules above exist)

1. Prism pre-launch took >10 min on the HDD + 2.4 GHz Wi-Fi (metadata/account refresh, Java check, asset
   downloads) and exceeded the launch grace twice; SSH dropped while the link was saturated.
2. Stopping a background Git Bash campaign stopped only the parent shell; the loop kept going and started a
   Prism run on top of a launcher run. Kill drivers by command line (or PID file), then `Recover` the
   transactions in reverse order.
3. The launcher rebrand moved the data directory from `%APPDATA%\PandoraLauncher` to
   `%APPDATA%\WachilandLauncher` without migration: the instance, account, `libraries`, `assets`,
   `logconfigs` and `metadata` had to be re-created/copied. The copied instance kept the old
   `preferred_account`, so the launcher opened a browser login and never started Java.
4. Task Scheduler refused tasks registered without battery settings (`0x800710E0`).

## Known machine issues

- **The HDD is failing (2026-10-14).** 162 uncorrected read errors (`Get-StorageReliabilityCounter`), System
  events `disk` 7 (bad block) and `storahci` 129 (controller reset) in bursts, and on 2026-10-14 a continuous
  storm (one bad block and one reset per minute for 20+ minutes) that froze a game during resource loading and
  dropped SSH. **The laptop is not valid for startup timing until the disk is replaced**: a single read on a bad
  sector stalls the whole disk for tens of seconds, which alone explains "random" run-to-run variance. Before
  any campaign, check the last hour of `disk`/`storahci` events; any hit invalidates the run.
- After the disk is replaced, control the remaining variance sources before measuring: Defender exclusions for
  the launcher data dir and JDKs (the old `PandoraLauncher` dir was excluded, the new `WachilandLauncher` dir was
  not), `WSearch`/`SysMain` stopped during campaigns, a purged standby list before each run (all runs equally
  cold), and low-rate system counters (CPU, disk, paging, `% Processor Performance`) recorded per run.
- 7.9 GB physical RAM with `-Xmx6G`; no low-virtual-memory events were recorded during the world-entry crashes.

## World-entry reproduction (`world_entry_repro.ps1`)

The launcher has no CLI quick-play: it starts `com.moulberry.pandora.LaunchWrapper` and sends the game
arguments through stdin. `world_entry_repro.ps1 -Action Capture` records the launcher's JVM argv once;
`-Action Run -World <name>` starts Java directly with those args and feeds the stdin protocol with
`--quickPlaySingleplayer`, so a world entry needs nobody at the laptop. `-Env 'ALSOFT_LOGLEVEL=3','ALSOFT_LOGFILE={run}\alsoft.log'`
enables OpenAL Soft logging. For isolation, move mod JARs out of `mods` (and edit `options.txt`) inside a
`try/finally` that always restores them. WER local dumps for `javaw.exe` are enabled (minidumps to
`C:\BootOptimBench\controller\worldentry\dumps`).

Findings 2026-10-14 (reported to WachilandEliteCompanion through wbombo `decisions.md`): the client renders one
world frame and dies ~30–70 s after `joined the game` with `abort()` (`0xc0000409`, fast-fail 7) inside LWJGL
`OpenAL.dll` 1.23.1 at offset `0xa2b05`, as in September. It still reproduces without MCEF+WebDisplays and
without AnalogAudio. OpenAL Soft logs a healthy context (stereo float32 48 kHz) and no error before the abort.
The master-volume-0 isolation was invalidated by the disk storm; it is the next step once the disk is replaced.

# Physical PC startup bench (Wachiland Launcher)

Startup A/B on a physical Windows PC through the Wachiland Launcher, for JVM-level questions (JVM flags, JDK
version, simulated core counts). Used for the 2026-10-15 fast-PC sweep in
[`docs/research/jit-compiler-pressure-2026-10-14.md`](../../docs/research/jit-compiler-pressure-2026-10-14.md).
For the low-end laptop use [`../laptop-bench/`](../laptop-bench/README.md) instead.

## Setup (once)

- A launcher exe (CI artifact `pandora-windows-x86_64-<sha7>` of BootOptimPandora) and a Beta instance created
  from its UI. Note the Beta revision (`PACK.md` in wbombo) and keep it fixed for a series (`--no-update`).
- Put the BootOptim CI JAR (`bootoptim-<sha>` artifact, `bootoptim-ci.jar`) in the instance `mods/` and set
  `startupLog=true` in `config/boot_optim.properties`.
- Close the launcher; nothing else heavy running (builds, games, downloads, extraction).

## Run

```powershell
.\campaign.ps1 -Plan plan.txt -Exe <launcher.exe> -Out C:\BootOptimBench\local\runs
```

Plan lines: `tag|affinityMaskHex|extra JVM flags|optional javaw.exe`. `run_once.ps1` writes `jvm_flags` (adds
`-Dboot_optim.benchmark.exitOnTitle=true` and a per-run `-Dboot_optim.benchRunId` marker) and `jvm_binary` into
the instance `info_v1.json`, starts the launcher under `cmd /c start /affinity <mask>` with
`--run-instance-normal <instance> --no-update`, binds Java by the marker, waits for it to exit at the title
screen and appends one line (`mod_entrypoint_ms`, `main_menu_ms`, affinity, Java path) to `results.txt`.

## Rules

- **Simulating fewer cores needs both** an affinity mask and `-XX:ActiveProcessorCount=N`: on Windows HotSpot
  ignores the affinity mask when sizing compiler/GC threads. Check once with `-CheckFlags` (`jcmd VM.flags`).
- Order: one discarded primer per class/JVM, then ABBA ×2 (ABCCBA ×2 for three arms); compare medians and
  whether all candidates fall outside the control range.
- A launcher or game left running blocks the next run (`launcher running`); kill both before relaunching.
- In PowerShell, names are case-insensitive: never reuse a parameter name (`$JavaPath`) for a local variable.

## Class sources (AOT cache)

`aot_class_sources.ps1 -ClassLoadLog <log> -Report <bootoptim-startup.log> [-TrainingLog <log>]`
counts, up to `mod_entrypoint` and `main_menu`, where classes come from:
`AOT/CDS` (non-JDK class served from the cache), `JDK-CDS`, `DEFINED` (custom loader, parsed),
`MISMATCH` (loaded in training too, but bytes differ now, so the cache is rejected for it), `JAR`, `JRT`.
The log needs `-Xlog:class+load=info:file=<path>:uptime`. See
[`docs/research/jdk25-aot-cache-reopen-2026-10-15.md`](../../docs/research/jdk25-aot-cache-reopen-2026-10-15.md).

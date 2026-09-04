# Laptop benchmark remote access

This folder contains the **one-time enrolment** script for a slow Windows laptop
used as BootOptim's final performance gate. The design deliberately uses SSH
only for dispatch and artifact collection. It does not use RDP, TeamViewer, a
self-hosted Actions runner, or polling while Minecraft is running; all of those
can perturb a graphics/resource-load benchmark.

## What the enrolment changes

`Enable-BootOptimRemote.ps1` must be run once from an elevated PowerShell
window in the Windows account that will run Minecraft. It:

1. installs/enables the built-in OpenSSH Server if needed;
2. adds one supplied public key to Windows' correct per-user or administrator
   authorized-keys location, then enables key-only SSH authentication;
3. disables Windows' broad OpenSSH firewall rule and replaces it with one that
   permits TCP/22 only from the controller's single LAN IP address;
4. creates `C:\BootOptimBench\{incoming,results,state}`.

It does **not** change Java, Minecraft, graphics drivers, power settings,
resource packs, or the game instance. It intentionally does not create a game
task until the exact launcher and isolated benchmark-instance paths have been
inspected through the new SSH connection.

## Initial enrolment

On the controller PC, create a dedicated key (never reuse a personal SSH key):

```powershell
ssh-keygen -t ed25519 -a 64 -f "$env:USERPROFILE\.ssh\bootoptim-laptop-ed25519-v2" -C "bootoptim-laptop-controller"
Get-Content "$env:USERPROFILE\.ssh\bootoptim-laptop-ed25519-v2.pub"
```

Copy only the resulting public-key line. Never copy or send the private-key
file.

On the laptop, open **PowerShell as administrator**, download this script from
the reviewed branch or copy it locally, then run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
& .\Enable-BootOptimRemote.ps1 `
  -ControllerAddress '192.168.1.50' `
  -ControllerPublicKey 'ssh-ed25519 AAAA... bootoptim-laptop-controller'
```

Replace the IP with the controller PC's current LAN IPv4 address and replace
the key with the full public-key line. The script prints a
`BOOTOPTIM_REMOTE_READY` block. Give that block to the controller; it contains
no secret.

The controller verifies access with:

```powershell
ssh -i "$env:USERPROFILE\.ssh\bootoptim-laptop-ed25519-v2" USER@LAPTOP_IP hostname
```

## Isolated Prism benchmark instance

`Clone-BootOptimBenchInstance.ps1` is the second, controller-dispatched step.
It creates the disposable game directory at
`C:\BootOptimBench\prism\instances\BootOptimBench\.minecraft` from the pack
inputs needed at launch. It deliberately excludes saves, logs, `.bootoptim`,
`.cache`, MCEF caches, launcher data, accounts, crash reports, and other mutable
runtime outputs. It also refuses to overwrite a non-empty target.

Run it only after Prism portable has been installed and the live profile path
has been read through SSH. Check `C:\BootOptimBench\state\instance-clone.json`
for its terminal status before configuring or launching Prism.

`Configure-BootOptimPrismInstance.ps1` then writes the two minimal Prism
metadata files. It pins Minecraft 1.21.1, NeoForge 21.1.248, the inspected Java
25.0.4 runtime, 6 GiB maximum heap, and the user's G1 tuning. It intentionally
does not carry the live profile's diagnostic
`-Dboot_optim.profileLevelRendererReload=true` into a performance baseline. It
does add BootOptim's low-overhead startup marker and title-screen auto-exit
properties, which are required for unattended benchmark result collection.

`Initialize-BootOptimPrismPortable.ps1` performs Prism's one-time portable
configuration. It never copies a Microsoft credential. Prism 11 requires an
owned Microsoft account to be signed in once through its own UI before it will
launch an offline game session; that login remains in the isolated portable
Prism directory and is never read or handled by these scripts. Every benchmark
job subsequently launches with Prism's `--offline BootOptimBench` option.

The portable `prismlauncher.cfg` keeps the inspected Java 25 runtime and sets
Prism's Java-compatibility warning override in `[General]`. This is not a Java
or OS change: it prevents Prism from replacing the pack's actual Java 25 with
the Java 21 value advertised by 1.21.1 metadata.

Before any graphical launch, `Test-BootOptimInteractiveSession.ps1` is run via
a scheduled task with an **interactive-token** logon type. Its state record
must show the logged-in desktop's session ID and `userInteractive: true`; SSH's
session alone is never used to launch Minecraft.

## Interactive benchmark runner

`Invoke-BootOptimPrismBenchmark.ps1` is the action of a scheduled task with an
**interactive-token** logon type. It starts only the isolated Prism instance,
resolves its UUID from `instance.cfg` (Prism's CLI requires that UUID, not the
display name), and launches it offline from the logged-in graphical session.
It detects the newly created Java PID, waits on that process without polling
game logs, then records the terminal BootOptim title-screen summary in
`C:\BootOptimBench\results\RUN_ID.json`.

It must not run from SSH/session 0, and it refuses to start if another Java
process is already running in the benchmark desktop session.

On a fresh portable Prism start it waits for the bounded pre-Java component
resolution interval before issuing the CLI launch. That setup interval is not
included in BootOptim's `main_menu` measurement; it exists solely because Prism
does not queue launch requests received before its instance profile is ready.

## Benchmark hygiene

- Keep the laptop plugged in, on the same LAN, logged in, and do not attach RDP
  while a run is active.
- A normal campaign is `control -> candidate -> control` using one JAR and a
  JVM feature switch. It has no JFR or high-cardinality profiler.
- Do not reboot Windows between normal runs. Boot/restart is a separate cold-I/O
  scenario reserved for candidates that deliberately affect physical cache or
  read-ahead behavior.
- Stop here and fix the network security before widening the firewall rule or
  forwarding port 22 on a router. Neither is required.

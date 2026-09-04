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

## Deliberately deferred second step

Minecraft needs a real logged-in graphical session. A generic service or an SSH
child process cannot safely launch the benchmark because it would use Windows
session 0 or an unknown launcher profile. After SSH works, inspect the laptop
once to establish:

- the exact instance/launcher type and its Java launch command;
- a dedicated benchmark copy of that instance, never the user's live one;
- the paths to `latest.log` and `bootoptim-startup.log`;
- the Java process PID returned by the launcher.

Only then install an interactive scheduled task that receives a job file,
launches the exact game command, waits on that PID without log polling, and
collects a result ZIP after BootOptim's existing title-screen auto-exit.

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

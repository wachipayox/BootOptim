# World-entry reproduction on the physical laptop (diagnostic only; see README.md).
#
# The Wachiland/Pandora launcher starts Java with com.moulberry.pandora.LaunchWrapper and sends the game
# arguments through stdin ("arg"/<value> pairs, then "launch"/<main class>), so the launcher has no CLI
# quick-play. This script:
#   Capture  starts the instance once through the launcher, records Java's JVM argv and executable, copies the
#            natives directory, then stops Java and the launcher.
#   Run      starts Java directly with those JVM args in the logged-on session, feeds the LaunchWrapper protocol
#            with --quickPlaySingleplayer <world> and access token "0" (enough for a local world), waits, then
#            collects latest.log, hs_err, crash reports and WER events (1000/1001/1026).
# Username/UUID come from a previous latest.log ("ModLauncher running: args"); never put them in this repo.
param(
    [Parameter(Mandatory = $true)][ValidateSet('Capture','Run')][string]$Action,
    [Parameter(Mandatory = $true)][string]$InteractiveUser,
    [string]$World = 'New World',
    [string]$Tag = 'base',
    [string]$ExtraJvm = '',
    [string]$Uuid = '',
    [string]$Username = '',
    [string]$LauncherExe = 'C:\BootOptimBench\tooling\wachiland-launcher\WachilandLauncher.exe',
    [string]$InstanceName = 'BootOptimLaptop',
    [string]$Root = 'C:\BootOptimBench\controller\worldentry',
    [int]$Minutes = 15,
    [string[]]$Env = @()
)
$ErrorActionPreference = 'Stop'
$launcherData = Join-Path $env:APPDATA 'WachilandLauncher'
$game = Join-Path $launcherData "instances\$InstanceName\.minecraft"
New-Item -ItemType Directory -Force $Root | Out-Null
Add-Type -Namespace WE -Name Argv -MemberDefinition @'
[DllImport("shell32.dll", SetLastError = true)] static extern IntPtr CommandLineToArgvW([MarshalAs(UnmanagedType.LPWStr)] string cmd, out int n);
[DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr p);
public static string[] Split(string cmd) {
    int n; IntPtr p = CommandLineToArgvW(cmd, out n); if (p == IntPtr.Zero) return new string[0];
    try { var a = new string[n]; for (int i = 0; i < n; i++) a[i] = Marshal.PtrToStringUni(Marshal.ReadIntPtr(p, i * IntPtr.Size)); return a; }
    finally { LocalFree(p); }
}
'@
function Task([string]$name, [string]$exe, [string]$arguments, [string]$cwd) {
    $s = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Hours 2)
    $p = New-ScheduledTaskPrincipal -UserId $InteractiveUser -LogonType Interactive -RunLevel Limited
    $a = if ($cwd) { New-ScheduledTaskAction -Execute $exe -Argument $arguments -WorkingDirectory $cwd } else { New-ScheduledTaskAction -Execute $exe -Argument $arguments }
    Register-ScheduledTask -TaskName $name -Action $a -Principal $p -Settings $s -Force | Out-Null
    Start-ScheduledTask -TaskName $name
}
# Java @argfiles treat backslash as an escape inside quotes.
function Q([string]$t) { '"' + $t.Replace('\', '\\').Replace('"', '\"') + '"' }

switch ($Action) {
'Capture' {
    Task 'BenchWorldEntryCapture' $LauncherExe "--run-instance-normal $InstanceName" $null
    $j = $null; $deadline = (Get-Date).AddMinutes(10)
    while (-not $j -and (Get-Date) -lt $deadline) { Start-Sleep 2; $j = Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -match 'WachilandLauncher' } | Select-Object -First 1 }
    if (-not $j) { throw 'no Java from launcher' }
    # The launcher creates Java without a program name in the command line: argv[0] is already a JVM option.
    $argv = [WE.Argv]::Split($j.CommandLine)
    $natives = ($argv | Where-Object { $_ -like '-Djava.library.path=*' } | Select-Object -First 1) -replace '^-Djava\.library\.path=', ''
    if ($natives -and (Test-Path $natives)) { Remove-Item "$Root\natives" -Recurse -Force -EA SilentlyContinue; Copy-Item $natives "$Root\natives" -Recurse }
    Stop-Process -Id $j.ProcessId -Force; Start-Sleep 2
    Get-Process WachilandLauncher -EA SilentlyContinue | Stop-Process -Force
    Unregister-ScheduledTask -TaskName BenchWorldEntryCapture -Confirm:$false
    $out = foreach ($a in $argv) { if ($a -like '-Djava.library.path=*') { "-Djava.library.path=$Root\natives" } else { $a } }
    Set-Content "$Root\java.txt" $j.ExecutablePath
    [IO.File]::WriteAllLines("$Root\args.txt", ($out | ForEach-Object { Q $_ }))
    'captured ' + $out.Count + ' JVM args; java=' + $j.ExecutablePath + '; natives copied=' + (Test-Path "$Root\natives")
}
'Run' {
    if (-not $Uuid -or -not $Username) { throw '-Uuid and -Username are required' }
    $java = (Get-Content "$Root\java.txt").Trim()
    $run = "$Root\$Tag-$(Get-Date -Format HHmmss)"; New-Item -ItemType Directory $run | Out-Null
    $extra = @($ExtraJvm -split ' ' | Where-Object { $_ } | ForEach-Object { Q $_ })
    $argfile = "$run\args.txt"
    [IO.File]::WriteAllLines($argfile, [string[]](@($extra) + @(Get-Content "$Root\args.txt")))
    $gameArgs = @('--username', $Username, '--version', '1.21.1', '--gameDir', $game, '--assetsDir', (Join-Path $launcherData 'assets'),
        '--assetIndex', '17', '--uuid', $Uuid, '--accessToken', '0', '--userType', 'msa', '--versionType', 'release',
        '--fml.neoForgeVersion', '21.1.248', '--fml.fmlVersion', '4.0.43', '--fml.mcVersion', '1.21.1', '--fml.neoFormVersion', '20240808.144430',
        '--launchTarget', 'forgeclient', '--quickPlaySingleplayer', $World)
    $stdin = New-Object System.Collections.Generic.List[string]
    foreach ($g in $gameArgs) { $stdin.Add('arg'); $stdin.Add($g) }
    $stdin.Add('launch'); $stdin.Add('cpw.mods.bootstraplauncher.BootstrapLauncher')
    [IO.File]::WriteAllLines("$run\stdin.txt", $stdin)
    # -Env 'NAME=value' entries become `set` lines; {run} expands to the run directory (e.g. ALSOFT_LOGFILE={run}\alsoft.log).
    $envLines = ($Env | ForEach-Object { 'set "' + $_.Replace('{run}', $run) + '"' }) -join "`r`n"
    Set-Content "$run\go.cmd" ("@echo off`r`n" + $envLines + "`r`ncd /d `"$game`"`r`n`"$java`" @`"$argfile`" < `"$run\stdin.txt`"")
    $start = Get-Date
    Task 'BenchWorldEntryRun' 'cmd.exe' "/c `"$run\go.cmd`"" $game
    $j = $null; $deadline = (Get-Date).AddMinutes(2)
    while (-not $j -and (Get-Date) -lt $deadline) { Start-Sleep 2; $j = Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape($argfile) } | Select-Object -First 1 }
    if (-not $j) { throw 'Java did not start from argfile' }
    $p = Get-Process -Id $j.ProcessId
    $exited = $p.WaitForExit($Minutes * 60 * 1000)
    $end = Get-Date
    if (-not $exited) { 'SURVIVED ' + $Minutes + ' min after start; stopping'; Stop-Process -Id $j.ProcessId -Force }
    else { 'EXITED after ' + [int]($end - $start).TotalSeconds + ' s' }
    Start-Sleep 5
    Unregister-ScheduledTask -TaskName BenchWorldEntryRun -Confirm:$false -EA SilentlyContinue
    Copy-Item "$game\logs\latest.log" "$run\latest.log" -EA SilentlyContinue
    Get-ChildItem $game -Filter 'hs_err*.log' | Where-Object { $_.LastWriteTime -gt $start } | Copy-Item -Destination $run
    Get-ChildItem "$game\crash-reports" -EA SilentlyContinue | Where-Object { $_.LastWriteTime -gt $start } | Copy-Item -Destination $run
    Get-WinEvent -FilterHashtable @{LogName = 'Application'; StartTime = $start} -EA SilentlyContinue | Where-Object { $_.Id -in 1000, 1001, 1026 } | ForEach-Object { "[$($_.Id)] " + ($_.Message -replace '\s+', ' ') } | Set-Content "$run\events.txt"
    'run dir ' + $run
    Get-Content "$run\events.txt" -EA SilentlyContinue | Select-Object -First 3 | ForEach-Object { $_.Substring(0, [Math]::Min(400, $_.Length)) }
    Select-String "$run\latest.log" -Pattern 'joined the game|Preparing spawn|Stopping!|FATAL|Exception in' -EA SilentlyContinue | Select-Object -Last 6 | ForEach-Object { $_.Line }
}
}

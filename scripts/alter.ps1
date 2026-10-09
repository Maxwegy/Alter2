<#
Alter2 server launcher (Windows PowerShell 5.1+).

  .\scripts\alter.ps1 up [-Build]        start in the background (builds the distribution if missing)
  .\scripts\alter.ps1 down [ticks]       graceful stop: countdown, everyone logged out and saved, then exit
  .\scripts\alter.ps1 restart [ticks]    graceful restart
  .\scripts\alter.ps1 status             health of the running server
  .\scripts\alter.ps1 logs [-Follow]     server log (data\logs\alter.log)

The server writes data\run\server.json (pid, admin port, token) while it runs; this script talks to its local
admin API (127.0.0.1 only). A hidden supervisor restarts the server when it exits with code 75 (restart
requested by `restart` or ::update) and stops on any other exit.
#>
param(
    [Parameter(Position = 0)][string]$Command,
    [Parameter(Position = 1)][int]$Ticks = 0,
    [switch]$Build,
    [switch]$Follow
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RunDir = Join-Path $Root 'data\run'
$RunFile = Join-Path $RunDir 'server.json'
$LogDir = Join-Path $Root 'data\logs'
$Lib = Join-Path $Root 'game-server\build\install\game-server\lib'
$MainClass = 'org.alter.game.Launcher'
$RestartExitCode = 75

function Get-RunInfo {
    if (Test-Path $RunFile) { return Get-Content $RunFile -Raw | ConvertFrom-Json }
    return $null
}

function Test-Alive([object]$ProcessId) {
    if ($null -eq $ProcessId) { return $false }
    return $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Invoke-Admin([string]$Method, [string]$Path) {
    $info = Get-RunInfo
    $headers = @{ Authorization = "Bearer $($info.adminToken)" }
    return Invoke-RestMethod -Method $Method -Uri "http://127.0.0.1:$($info.adminPort)$Path" -Headers $headers -TimeoutSec 10
}

# Java 17+: JAVA_HOME first, then PATH. The Gradle start script is not used because its classpath is longer
# than cmd.exe allows; java takes a lib\* wildcard instead.
function Get-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($java in $candidates) {
        if (-not (Test-Path $java)) { continue }
        $version = (& cmd /c "`"$java`" -version 2>&1") -join ' '
        if ($version -match 'version "(\d+)') { if ([int]$Matches[1] -ge 17) { return $java } }
    }
    throw 'Java 17 or newer is required. Set JAVA_HOME to a JDK 17 (Gradle provisions one under %USERPROFILE%\.gradle\jdks).'
}

function Invoke-Build {
    Write-Host 'Building the server distribution...'
    Push-Location $Root
    try { & .\gradlew.bat --console=plain -q :game-server:installDist; if ($LASTEXITCODE -ne 0) { throw 'Build failed.' } }
    finally { Pop-Location }
}

function Start-Server {
    $info = Get-RunInfo
    if ($info -and (Test-Alive $info.pid)) { Write-Host "Already running (pid $($info.pid))."; return }
    if ($Build -or -not (Test-Path $Lib)) { Invoke-Build }
    $java = Get-Java
    $javaOpts = if ($env:ALTER_JAVA_OPTS) { $env:ALTER_JAVA_OPTS } else { '-Xmx3g' }
    New-Item -ItemType Directory -Force $RunDir, $LogDir | Out-Null
    if (Test-Path $RunFile) { Remove-Item $RunFile }

    # The server resolves ..\data and ..\game.yml, so it runs from game-server\.
    $console = Join-Path $LogDir 'console.log'
    $supervisor = @"
`$ErrorActionPreference = 'Continue'
Set-Location '$Root\game-server'
while (`$true) {
    & cmd /c "`"$java`" $javaOpts -cp `"$Lib\*`" $MainClass >> `"$console`" 2>&1"
    `$code = `$LASTEXITCODE
    Add-Content '$console' "[`$((Get-Date).ToUniversalTime().ToString('s'))Z] server exited with code `$code"
    if (`$code -ne $RestartExitCode) { break }
}
Remove-Item '$RunDir\supervisor.pid' -ErrorAction SilentlyContinue
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($supervisor))
    $proc = Start-Process powershell -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-EncodedCommand', $encoded -WindowStyle Hidden -PassThru
    Set-Content (Join-Path $RunDir 'supervisor.pid') $proc.Id

    Write-Host -NoNewline 'Starting'
    for ($i = 0; $i -lt 180; $i++) {
        if (Test-Path $RunFile) {
            try { Invoke-Admin GET /health | Out-Null; Write-Host ''; Show-Status; return } catch { }
        }
        if (-not (Test-Alive $proc.Id)) { Write-Host ''; throw "Server exited during startup; see $console" }
        Write-Host -NoNewline '.'; Start-Sleep -Seconds 1
    }
    Write-Host ''; throw "Server did not report healthy within 180s; see $console"
}

function Request-Stop([int]$Countdown, [bool]$Restart) {
    $info = Get-RunInfo
    if (-not $info -or -not (Test-Alive $info.pid)) { Write-Host 'Not running.'; return $null }
    try {
        Invoke-Admin POST "/shutdown?ticks=$Countdown&restart=$($Restart.ToString().ToLower())" | Out-Null
    } catch {
        Write-Warning 'Admin API unreachable. Windows cannot send a graceful signal; stopping the process (players are autosaved every few minutes).'
        Stop-Process -Id $info.pid -Force
    }
    return $info.pid
}

function Stop-Server {
    $serverPid = Request-Stop $Ticks $false
    if ($null -eq $serverPid) { return }
    $limit = [int]($Ticks * 0.6) + 60
    Write-Host -NoNewline 'Stopping'
    for ($i = 0; $i -lt $limit; $i++) {
        if (-not (Test-Alive $serverPid)) { Write-Host ''; Write-Host 'Stopped.'; return }
        Write-Host -NoNewline '.'; Start-Sleep -Seconds 1
    }
    Write-Host ''; Write-Warning "Still running after ${limit}s; forcing it to stop."
    Stop-Process -Id $serverPid -Force
}

function Restart-Server {
    if ($null -ne (Request-Stop $Ticks $true)) { Write-Host 'Restart requested; the supervisor starts it again. Check with: .\scripts\alter.ps1 status' }
}

function Show-Status {
    $info = Get-RunInfo
    if ($info -and (Test-Alive $info.pid)) {
        try { Invoke-Admin GET /health | ConvertTo-Json -Depth 4 } catch { Write-Host "Running (pid $($info.pid)) but the admin API did not answer." }
    } else { Write-Host 'Stopped.' }
}

function Show-Logs {
    $log = Join-Path $LogDir 'alter.log'
    if ($Follow) { Get-Content $log -Tail 50 -Wait } else { Get-Content $log -Tail 200 }
}

switch ($Command) {
    'up' { Start-Server }
    'down' { Stop-Server }
    'restart' { Restart-Server }
    'status' { Show-Status }
    'logs' { Show-Logs }
    'build' { Invoke-Build }
    default { Get-Help $PSCommandPath; exit 1 }
}

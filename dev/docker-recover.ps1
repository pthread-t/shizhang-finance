[CmdletBinding()]
param(
    [ValidateRange(30, 300)]
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $dockerCommand) { throw "Docker CLI is not installed." }
$dockerExe = $dockerCommand.Source
$desktopExe = "C:\Program Files\Docker\Docker\Docker Desktop.exe"

function Test-DockerReady {
    $probe = Start-Process -FilePath $dockerExe -ArgumentList @("info", "--format", "{{.ServerVersion}}") -WindowStyle Hidden -PassThru
    if (-not $probe.WaitForExit(5000)) {
        $probe.Kill()
        $probe.WaitForExit()
        return $false
    }
    return $probe.ExitCode -eq 0
}

if (Test-DockerReady) { return }
if (-not (Test-Path -LiteralPath $desktopExe)) { throw "Docker Desktop is not installed at $desktopExe" }

Write-Host "Docker engine is unavailable; isolating stale Windows AF_UNIX socket directories." -ForegroundColor Yellow
$dockerProcesses = @(
    "Docker Desktop",
    "com.docker.backend",
    "com.docker.build",
    "docker",
    "docker-ai",
    "docker-offload",
    "docker-mcp",
    "DockerCli",
    "local-sandboxesd"
)
Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $dockerProcesses -contains $_.ProcessName } |
    Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# Docker Desktop can leave the WSL VM and its AF_UNIX handles alive after the
# Windows backend crashes.  Shut WSL down before moving the socket directories
# so the next Desktop launch starts with a single clean backend instance.
& wsl.exe --shutdown
if ($LASTEXITCODE -ne 0) { throw "Unable to shut down WSL before Docker recovery." }
Start-Sleep -Seconds 2

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$localAppData = [System.IO.Path]::GetFullPath($env:LOCALAPPDATA)
$socketDirectories = @(
    [System.IO.Path]::GetFullPath((Join-Path $localAppData "Docker\run")),
    [System.IO.Path]::GetFullPath((Join-Path $localAppData "docker-secrets-engine"))
)

foreach ($source in $socketDirectories) {
    if (-not $source.StartsWith($localAppData + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Resolved socket path is outside Local AppData: $source"
    }
    if (-not (Test-Path -LiteralPath $source)) { continue }
    $target = "$source.stale-$stamp"
    if (Test-Path -LiteralPath $target) { throw "Socket backup target already exists: $target" }
    Move-Item -LiteralPath $source -Destination $target
    Write-Host "Preserved stale socket directory: $target"
}

Start-Process -FilePath $desktopExe -WindowStyle Hidden | Out-Null
$deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
do {
    Start-Sleep -Seconds 3
    if (Test-DockerReady) {
        Write-Host "Docker Desktop is ready." -ForegroundColor Green
        return
    }
} while ([DateTime]::UtcNow -lt $deadline)

throw "Docker Desktop did not become ready within $StartupTimeoutSeconds seconds."

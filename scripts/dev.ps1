param([switch]$Build, [switch]$StartDocker)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'docker-command.ps1')
$dockerCli = Get-DockerCommand
if ($StartDocker) {
    & $dockerCli desktop start --timeout 120
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop could not start. Check WSL/Virtual Machine Platform and restart Windows if setup requested it.' }
}
$dockerOs = & $dockerCli info --format '{{.OSType}}' 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker engine is unavailable. Start Docker Desktop with Linux containers. If WSL cannot start, check Virtual Machine Platform and hardware virtualization, then rerun this script.'
}
if ($dockerOs -ne 'linux') { throw 'This project requires Docker Linux containers.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$artifactPath = Join-Path $projectRoot '.runtime\artifacts'
New-Item -ItemType Directory -Force -Path $artifactPath | Out-Null
$env:ARTIFACT_HOST_PATH = (Resolve-Path -LiteralPath $artifactPath).Path.Replace('\', '/')
Push-Location $projectRoot
try {
    if ($Build) { & (Join-Path $PSScriptRoot 'build-analyzers.ps1') }
    & $dockerCli compose up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed' }
    $deadline = (Get-Date).AddMinutes(3)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health/readiness' -TimeoutSec 5
            $ui = Invoke-WebRequest 'http://127.0.0.1:8088' -UseBasicParsing -TimeoutSec 5
            if ($health.status -eq 'UP' -and $ui.StatusCode -eq 200) { $ready = $true; break }
        } catch {
            # Startup is asynchronous; keep waiting within the bounded readiness window.
        }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'API/UI did not become ready within three minutes. Inspect docker compose logs --tail 100.' }
    Write-Output 'Semantic Business Map: http://127.0.0.1:8088'
} finally { Pop-Location }

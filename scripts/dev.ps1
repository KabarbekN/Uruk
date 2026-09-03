param([switch]Build)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$artifactPath = Join-Path $projectRoot '.runtime\artifacts'
New-Item -ItemType Directory -Force -Path $artifactPath | Out-Null
$env:ARTIFACT_HOST_PATH = (Resolve-Path -LiteralPath $artifactPath).Path.Replace('\', '/')
Push-Location $projectRoot
try {
    if ($Build) { & (Join-Path $PSScriptRoot 'build-analyzers.ps1') }
    & docker compose up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed' }
    Write-Output 'Semantic Business Map: http://127.0.0.1:8088'
} finally { Pop-Location }

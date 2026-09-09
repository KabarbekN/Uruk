$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'docker-command.ps1')
$dockerCli = Get-DockerCommand
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & $dockerCli build -t semanticmap/java-spring:0.1.0 -f analyzers/java-spring/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'Java analyzer build failed' }
    & $dockerCli build -t semanticmap/tree-sitter:0.1.0 -f analyzers/tree-sitter/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'Tree-sitter analyzer build failed' }
    & $dockerCli build -t semanticmap/postgresql:0.1.0 -f analyzers/postgresql/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL analyzer build failed' }
    & $dockerCli build -t semanticmap/typescript-node:0.1.0 -f analyzers/typescript-node/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'TypeScript analyzer build failed' }
} finally { Pop-Location }

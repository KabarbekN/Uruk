$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & docker build -t semanticmap/java-spring:0.1.0 -f analyzers/java-spring/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'Java analyzer build failed' }
    & docker build -t semanticmap/tree-sitter:0.1.0 -f analyzers/tree-sitter/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'Tree-sitter analyzer build failed' }
    & docker build -t semanticmap/postgresql:0.1.0 -f analyzers/postgresql/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL analyzer build failed' }
    & docker build -t semanticmap/typescript-node:0.1.0 -f analyzers/typescript-node/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'TypeScript analyzer build failed' }
} finally { Pop-Location }

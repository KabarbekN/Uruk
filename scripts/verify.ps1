param([switch]$Build, [switch]$Performance)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'docker-command.ps1')
$dockerCli = Get-DockerCommand
foreach ($tool in @('node', 'pnpm')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required. See README.md." }
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to a Java 21 JDK before running verification.'
}
function Invoke-Check {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Verification failed: $Program $($Arguments -join ' ') (exit $LASTEXITCODE)" }
}
$previousEnvironment = @{}
foreach ($key in @('E2E_API_URL', 'E2E_UI_URL', 'E2E_FIXTURE_ROOT', 'PLAYWRIGHT_BASE_URL')) {
    $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
Push-Location $projectRoot
try {
    Invoke-Check $dockerCli @('info', '--format', '{{.OSType}}')
    if ($Build) { & (Join-Path $PSScriptRoot 'dev.ps1') -Build }
    Invoke-Check (Join-Path $projectRoot 'mvnw.cmd') @('-B', '-ntp', 'spotless:check', 'verify')
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        Invoke-Check 'pnpm' @('install', '--frozen-lockfile')
        Invoke-Check 'pnpm' @('lint')
        Invoke-Check 'pnpm' @('test')
        Invoke-Check 'pnpm' @('build')
        $env:PLAYWRIGHT_BASE_URL = 'http://127.0.0.1:8088'
        Invoke-Check 'pnpm' @('exec', 'playwright', 'test', 'e2e/workspace.spec.ts', 'e2e/responsive-tooltips.spec.ts')
        if ($Performance) {
            Invoke-Check 'pnpm' @('exec', 'playwright', 'test', 'e2e/large-graph.spec.ts', 'e2e/renderer-stress.spec.ts', 'e2e/progressive-graph.spec.ts')
        }
    } finally { Pop-Location }
    # Parser tests run with the same native dependencies as the shipped images.
    $sandbox = @('run', '--rm', '--network=none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges', '--pids-limit=128', '--memory=512m', '--cpus=2', '--tmpfs', '/tmp:rw,nosuid,size=256m')
    $treeOutput = @('--tmpfs', '/opt/analyzers/tree-sitter/.test-output:rw,nosuid,size=256m')
    Invoke-Check $dockerCli ($sandbox + $treeOutput + @('--tmpfs', '/opt/analyzers/shared/.test-output:rw,nosuid,size=256m', '--entrypoint', 'python', 'semanticmap/tree-sitter:0.1.0', '-m', 'unittest', 'discover', '-s', '../shared/tests', '-v'))
    Invoke-Check $dockerCli ($sandbox + $treeOutput + @('--entrypoint', 'python', 'semanticmap/tree-sitter:0.1.0', '-m', 'unittest', 'discover', '-s', 'tests', '-v'))
    Invoke-Check $dockerCli ($sandbox + @('--tmpfs', '/opt/analyzers/postgresql/.test-output:rw,nosuid,size=256m', '--entrypoint', 'python', 'semanticmap/postgresql:0.1.0', '-m', 'unittest', 'discover', '-s', 'tests', '-v'))
    Invoke-Check $dockerCli ($sandbox + @('--tmpfs', '/opt/analyzers/typescript-node/.test-output:rw,nosuid,size=256m', '--entrypoint', 'node', 'semanticmap/typescript-node:0.1.0', '--test', 'tests/parser.test.mjs'))
    $env:E2E_API_URL = 'http://127.0.0.1:8080'
    $env:E2E_UI_URL = 'http://127.0.0.1:8088'
    $env:E2E_FIXTURE_ROOT = '/repository/fixtures'
    Invoke-Check 'node' @('scripts/e2e.mjs')
    Write-Output 'Local verification passed. The Compose stack remains available at http://127.0.0.1:8088.'
} finally {
    foreach ($key in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
    Pop-Location
}

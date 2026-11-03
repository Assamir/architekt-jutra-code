#Requires -Version 5.1
$ErrorActionPreference = 'Stop'

Set-Location $PSScriptRoot

# A JDK installed after this terminal was opened is not visible in its environment yet.
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User') }
}
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java not found. Install JDK 25 and set JAVA_HOME."
}
$mvnw = Join-Path $PSScriptRoot 'mvnw.cmd'

# --- Docker Compose ---
Write-Host "Ensuring docker-compose services are up..."
$running = docker compose ps --status running 2>$null | Select-String -Quiet 'postgres'
if ($running) {
    Write-Host "  Services already running."
} else {
    docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit code $LASTEXITCODE)" }
    Write-Host "  Postgres, LiteLLM, and Langfuse started."
}

$processes = @()

function Start-DevProcess([string]$Name, [string]$WorkingDirectory, [string]$Command) {
    Write-Host "Starting $Name..."
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $Command `
        -WorkingDirectory $WorkingDirectory -NoNewWindow -PassThru
    $null = $process.Handle  # keeps the handle open so ExitCode is available later
    $process | Add-Member -NotePropertyName DevName -NotePropertyValue $Name
    $process
}

try {
    # --- Host app (Spring Boot) ---
    $processes += Start-DevProcess 'host app' $PSScriptRoot "`"$mvnw`" -q spring-boot:run"

    # --- MCP app (Spring Boot) ---
    $mcpDir = Join-Path $PSScriptRoot 'mcp-server'
    $processes += Start-DevProcess 'MCP app' $mcpDir "`"$(Join-Path $mcpDir 'mvnw.cmd')`" -q spring-boot:run"

    # --- Plugins ---
    Get-ChildItem (Join-Path $PSScriptRoot 'plugins') -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'package.json') } |
        ForEach-Object {
            $script:processes += Start-DevProcess "plugin: $($_.Name)" $_.FullName 'npm run dev'
        }

    Write-Host ""
    Write-Host "All services starting. Press Ctrl+C to stop everything."
    $reported = @{}
    while ($true) {
        foreach ($p in $processes) {
            if ($p.HasExited -and -not $reported.ContainsKey($p.Id)) {
                Write-Warning "$($p.DevName) exited with code $($p.ExitCode)."
                $reported[$p.Id] = $true
            }
        }
        Start-Sleep -Seconds 1
    }
}
finally {
    Write-Host ""
    Write-Host "Shutting down..."
    foreach ($p in $processes) {
        if (-not $p.HasExited) {
            & taskkill.exe /PID $p.Id /T /F 2>&1 | Out-Null
        }
    }
}

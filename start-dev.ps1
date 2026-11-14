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

# --- Shared OAuth wiring (host + MCP server must agree; existing values win) ---
if (-not $env:APP_OAUTH2_ISSUER) { $env:APP_OAUTH2_ISSUER = 'http://localhost:8180' }
if (-not $env:AJ_OAUTH_SERVER_URL) { $env:AJ_OAUTH_SERVER_URL = $env:APP_OAUTH2_ISSUER }
if (-not $env:AJ_BACKEND_URL) { $env:AJ_BACKEND_URL = 'http://localhost:8180' }
if (-not $env:AJ_MCP_BASE_URL) { $env:AJ_MCP_BASE_URL = 'http://localhost:8181' }
if (-not $env:AJ_OAUTH_CLIENT_ID) { $env:AJ_OAUTH_CLIENT_ID = 'aj-mcp-server' }
# The client secret is reused from the gitignored .aj-dev.env, so either app can be restarted on its own.
# It is only put into the environment while the host and MCP processes are spawned (plugins never see it).
$inheritedClientSecret = $env:AJ_OAUTH_CLIENT_SECRET
$oauthClientSecret = $inheritedClientSecret
$devEnvFile = Join-Path $PSScriptRoot '.aj-dev.env'
if (-not $oauthClientSecret -and (Test-Path $devEnvFile)) {
    $secretLine = Get-Content $devEnvFile | Where-Object { $_ -match '^AJ_OAUTH_CLIENT_SECRET=' } | Select-Object -Last 1
    if ($secretLine) { $oauthClientSecret = $secretLine.Substring('AJ_OAUTH_CLIENT_SECRET='.Length).Trim() }
}
if (-not $oauthClientSecret) {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $oauthClientSecret = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
    Add-Content -Path $devEnvFile -Value "AJ_OAUTH_CLIENT_SECRET=$oauthClientSecret" -Encoding ASCII
    Write-Host "Generated AJ_OAUTH_CLIENT_SECRET and stored it in .aj-dev.env (gitignored)."
}
# APP_JWT_SECRET is deliberately not generated: it is inherited when set, otherwise the host's committed fallback applies.
Write-Host "OAuth issuer: $env:APP_OAUTH2_ISSUER"
Write-Host "MCP base URL: $env:AJ_MCP_BASE_URL"

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
    $env:AJ_OAUTH_CLIENT_SECRET = $oauthClientSecret
    try {
        # --- Host app (Spring Boot) ---
        $processes += Start-DevProcess 'host app' $PSScriptRoot "`"$mvnw`" -q spring-boot:run"

        # --- MCP app (Spring Boot) ---
        $mcpDir = Join-Path $PSScriptRoot 'mcp-server'
        $processes += Start-DevProcess 'MCP app' $mcpDir "`"$(Join-Path $mcpDir 'mvnw.cmd')`" -q spring-boot:run"
    }
    finally {
        Remove-Item Env:AJ_OAUTH_CLIENT_SECRET -ErrorAction SilentlyContinue
    }

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
    if ($inheritedClientSecret) { $env:AJ_OAUTH_CLIENT_SECRET = $inheritedClientSecret }
}

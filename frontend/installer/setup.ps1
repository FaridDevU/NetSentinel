param(
    [string]$InstallDir = $PSScriptRoot
)

$statusDir = "$env:APPDATA\NetSentinel"
$statusFile = "$statusDir\setup-status.txt"
$detailFile = "$statusDir\setup-detail.txt"

New-Item -ItemType Directory -Force -Path $statusDir | Out-Null

function Write-Status($msg, $detail = "") {
    $msg | Set-Content -Path $statusFile -Encoding UTF8
    if ($detail) {
        $detail | Set-Content -Path $detailFile -Encoding UTF8
    }
    Write-Output $msg
}

$resourcesDir = Split-Path -Parent $PSScriptRoot

$required = @{
    "backend.jar" = Join-Path $resourcesDir "backend.jar"
    "jre"         = Join-Path $resourcesDir "jre\bin\java.exe"
    "sandbox.exe" = Join-Path $resourcesDir "sandbox.exe"
}

$missing = @()
foreach ($name in $required.Keys) {
    if (-not (Test-Path $required[$name])) {
        $missing += $name
    }
}

if ($missing.Count -gt 0) {
    $detail = "Missing bundled resources: " + ($missing -join ", ")
    Write-Status "SETUP_FAILED" $detail
    Write-Output $detail
    exit 1
}

$nmap = Join-Path $resourcesDir "tools\nmap.exe"
$detail = "All bundled resources present."
if (-not (Test-Path $nmap)) {
    $detail = "Core resources present. Optional tool nmap.exe not bundled; network scans will be unavailable until it is added."
}

Write-Status "READY" $detail
Write-Output "Setup complete. NetSentinel is ready to use."

# NetSentinel prerequisites setup script
# Run as Administrator. Called by the NSIS installer post-install.
param(
    [string]$InstallDir = $PSScriptRoot
)

$statusDir = "$env:APPDATA\NetSentinel"
$statusFile = "$statusDir\setup-status.txt"

New-Item -ItemType Directory -Force -Path $statusDir | Out-Null

function Write-Status($msg) {
    $msg | Set-Content -Path $statusFile -Encoding UTF8
    Write-Output $msg
}

# -- Check WSL availability --
$wslExe = (Get-Command wsl -ErrorAction SilentlyContinue)
if (-not $wslExe) {
    Write-Output "Enabling WSL2 features (requires reboot)..."
    dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
    dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
    Write-Status "NEEDS_REBOOT"
    Write-Output "Please reboot and run this script again from: $PSCommandPath"
    exit 0
}

# -- Set WSL2 as default --
wsl --set-default-version 2 2>$null

# -- Check Kali installed --
$distros = wsl --list --quiet 2>$null
$kaliInstalled = $distros | Where-Object { $_ -match "kali" }

if (-not $kaliInstalled) {
    Write-Output "Installing Kali Linux (this may take several minutes)..."
    wsl --install --distribution kali-linux --no-launch 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Status "KALI_INSTALL_FAILED"
        Write-Output "ERROR: Failed to install Kali Linux. Try manually: wsl --install --distribution kali-linux"
        exit 1
    }
    Write-Status "NEEDS_REBOOT"
    Write-Output "Kali Linux installed. Please reboot, then run the app and click 'Complete Setup'."
    exit 0
}

# -- Install tools in Kali --
Write-Output "Installing tools in Kali Linux..."
wsl -d kali-linux -- bash -c "sudo apt-get update -qq 2>/dev/null && sudo apt-get install -y -qq nmap curl wget openjdk-21-jre-headless 2>/dev/null"

# -- Copy and start sandbox --
$wslPath = $InstallDir -replace "\\", "/"
$wslPath = "/mnt/" + $wslPath.Substring(0,1).ToLower() + $wslPath.Substring(2)

$sandboxSource = "$wslPath/resources/installer/netsentinel-sandbox"
wsl -d kali-linux -- bash -c "
    mkdir -p ~/.netsentinel
    if [ -f '$sandboxSource' ]; then
        cp '$sandboxSource' ~/.netsentinel/sandbox
        chmod +x ~/.netsentinel/sandbox
    fi
    # Kill any existing sandbox instance
    pkill -f netsentinel-sandbox 2>/dev/null || true
    # Start sandbox in background
    if [ -f ~/.netsentinel/sandbox ]; then
        nohup ~/.netsentinel/sandbox > ~/.netsentinel/sandbox.log 2>&1 &
        echo Sandbox started on port 7878
    else
        echo 'WARNING: sandbox binary not found — please compile the Rust sandbox and place it at: ~/.netsentinel/sandbox'
    fi
" 2>&1

Write-Status "READY"
Write-Output "Setup complete. NetSentinel is ready to use."

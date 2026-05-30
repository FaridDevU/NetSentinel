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

$wslExe = (Get-Command wsl -ErrorAction SilentlyContinue)
if (-not $wslExe) {
    Write-Output "Enabling WSL2 features (requires reboot)..."
    dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
    dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
    Write-Status "NEEDS_REBOOT" "Windows enabled WSL2 features and needs a restart."
    Write-Output "Please reboot and run this script again from: $PSCommandPath"
    exit 0
}

wsl --set-default-version 2 2>$null

$distros = wsl --list --quiet 2>$null
$kaliInstalled = $distros | Where-Object { $_ -match "kali" }

if (-not $kaliInstalled) {
    Write-Output "Installing Kali Linux (this may take several minutes)..."
    wsl --install --distribution kali-linux --no-launch 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Status "KALI_INSTALL_FAILED" "wsl --install --distribution kali-linux --no-launch failed with exit code $LASTEXITCODE."
        Write-Output "ERROR: Failed to install Kali Linux. Try manually: wsl --install --distribution kali-linux"
        exit 1
    }
    Write-Status "NEEDS_REBOOT" "Kali Linux was installed and Windows needs a restart before setup can continue."
    Write-Output "Kali Linux installed. Please reboot, then run the app and click 'Complete Setup'."
    exit 0
}

$wslPath = $InstallDir -replace "\\", "/"
$wslPath = "/mnt/" + $wslPath.Substring(0,1).ToLower() + $wslPath.Substring(2)

$lxssPath = "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Lxss"
$kaliEntry = Get-ChildItem $lxssPath -ErrorAction SilentlyContinue |
    Where-Object { (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue).DistributionName -match "kali" } |
    Select-Object -First 1
if ($kaliEntry) {
    Set-ItemProperty -Path $kaliEntry.PSPath -Name "DefaultUid" -Value 0 -ErrorAction SilentlyContinue
}

Write-Status "INSTALLING_TOOLS" "Installing nmap, gobuster, nikto, Java and PostgreSQL inside Kali."
Write-Output "Installing tools in Kali Linux..."
wsl -d kali-linux -- bash -c "sudo apt-get update -qq 2>/dev/null && sudo apt-get install -y -qq nmap curl wget openjdk-21-jre-headless gobuster nikto dirb postgresql postgresql-client 2>/dev/null && sudo mkdir -p /usr/share/wordlists && sudo ln -sf /usr/share/dirb/wordlists /usr/share/wordlists/dirb 2>/dev/null || true"

Write-Status "CONFIGURING_DB" "Creating local PostgreSQL user and database for NetSentinel."
Write-Output "Configuring PostgreSQL..."
wsl -d kali-linux -- bash -c "
    sudo service postgresql start 2>/dev/null

    PG_CONF=\$(sudo -u postgres psql -t -c 'SHOW config_file' 2>/dev/null | tr -d ' ')
    if [ -n \"\$PG_CONF\" ]; then
        sudo sed -i \"s/#listen_addresses = 'localhost'/listen_addresses = '127.0.0.1'/\" \"\$PG_CONF\" 2>/dev/null || true
    fi

    sudo -u postgres psql -tc \"SELECT 1 FROM pg_roles WHERE rolname='netsentinel'\" 2>/dev/null | grep -q 1 || \
        sudo -u postgres psql -c \"CREATE USER netsentinel WITH PASSWORD 'netsentinel'\" 2>/dev/null

    sudo -u postgres psql -tc \"SELECT 1 FROM pg_database WHERE datname='netsentinel'\" 2>/dev/null | grep -q 1 || \
        sudo -u postgres createdb -O netsentinel netsentinel 2>/dev/null

    sudo -u postgres psql -c \"GRANT ALL PRIVILEGES ON DATABASE netsentinel TO netsentinel\" 2>/dev/null

    CURRENT_USER=\$(whoami)
    echo \"\$CURRENT_USER ALL=(ALL) NOPASSWD: /usr/sbin/service postgresql start, /usr/sbin/service postgresql status\" | sudo tee /etc/sudoers.d/netsentinel > /dev/null

    sudo service postgresql restart 2>/dev/null
    echo PostgreSQL configured
" 2>&1

Write-Status "INSTALLING_BACKEND" "Copying backend.jar into ~/.netsentinel/backend.jar."
Write-Output "Installing backend..."
$jarSource = "$wslPath/resources/backend.jar"
wsl -d kali-linux -- bash -c "
    mkdir -p ~/.netsentinel
    if [ -f '$jarSource' ]; then
        cp '$jarSource' ~/.netsentinel/backend.jar
        echo Backend JAR installed
    else
        echo 'WARNING: backend.jar not found at $jarSource'
    fi
" 2>&1

Write-Status "INSTALLING_SANDBOX" "Copying sandbox binary into ~/.netsentinel/sandbox."
Write-Output "Installing sandbox..."
$sandboxSource = "$wslPath/resources/installer/netsentinel-sandbox"
wsl -d kali-linux -- bash -c "
    mkdir -p ~/.netsentinel
    if [ -f '$sandboxSource' ]; then
        cp '$sandboxSource' ~/.netsentinel/sandbox
        chmod +x ~/.netsentinel/sandbox
        echo Sandbox installed
    else
        echo 'WARNING: sandbox binary not found at $sandboxSource'
        echo 'Build it with: cargo build --release inside the sandbox/ directory'
    fi
" 2>&1

Write-Status "CREATING_SCRIPTS" "Creating ~/.netsentinel/start.sh."
Write-Output "Creating startup script..."
$startupScript = @'
#!/bin/bash
set -e

if [ -f "$HOME/.netsentinel/config.env" ]; then
    set -a
    source "$HOME/.netsentinel/config.env"
    set +a
fi

sudo service postgresql start 2>/dev/null || true
for i in $(seq 1 15); do
    pg_isready -h 127.0.0.1 -U netsentinel -d netsentinel -q 2>/dev/null && break
    sleep 1
done

pkill -f "$HOME/.netsentinel/sandbox" 2>/dev/null || true
sleep 1
nohup "$HOME/.netsentinel/sandbox" > "$HOME/.netsentinel/sandbox.log" 2>&1 &
sleep 1

exec java -jar "$HOME/.netsentinel/backend.jar" >> "$HOME/.netsentinel/backend.log" 2>&1
'@

$startupScript -replace "`r`n", "`n" | wsl -d kali-linux -- bash -c "mkdir -p ~/.netsentinel && cat > ~/.netsentinel/start.sh && sed -i 's/\r$//' ~/.netsentinel/start.sh && chmod +x ~/.netsentinel/start.sh && echo Startup script created" 2>&1

Write-Output "Verifying setup..."
wsl -d kali-linux -- bash -c "
    sudo service postgresql start 2>/dev/null || true
    if [ -f ~/.netsentinel/sandbox ]; then
        pkill -f netsentinel-sandbox 2>/dev/null || true
        nohup ~/.netsentinel/sandbox > ~/.netsentinel/sandbox.log 2>&1 &
        echo Sandbox started on port 7878
    fi
" 2>&1

$verify = wsl -d kali-linux -- bash -c "
    test -f ~/.netsentinel/backend.jar || { echo 'backend.jar missing'; exit 10; }
    test -x ~/.netsentinel/sandbox || { echo 'sandbox missing or not executable'; exit 11; }
    test -x ~/.netsentinel/start.sh || { echo 'start.sh missing or not executable'; exit 12; }
    sudo service postgresql start >/dev/null 2>&1 || true
    pg_isready -h 127.0.0.1 -U netsentinel -d netsentinel -q 2>/dev/null || { echo 'postgresql not ready'; exit 13; }
    echo 'setup verified'
" 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Status "SETUP_FAILED" $verify
    Write-Output $verify
    exit 1
}

Write-Status "READY" $verify
Write-Output "Setup complete. NetSentinel is ready to use."

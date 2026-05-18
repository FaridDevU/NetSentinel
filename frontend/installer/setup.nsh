!macro customInstall
  CreateDirectory "$APPDATA\NetSentinel"

  DetailPrint "Running prerequisite setup (WSL2 + Kali Linux)..."
  ExecWait 'powershell.exe -ExecutionPolicy Bypass -File "$INSTDIR\resources\installer\setup.ps1" "$INSTDIR"'

  FileOpen $0 "$APPDATA\NetSentinel\install-path.txt" w
  FileWrite $0 "$INSTDIR"
  FileClose $0
!macroend

!macro customUninstall
  ExecWait 'powershell.exe -Command "Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force"'
  ExecWait 'wsl -d kali-linux -- bash -c "pkill -f netsentinel-sandbox 2>/dev/null || true"'
!macroend

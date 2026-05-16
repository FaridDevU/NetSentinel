; NetSentinel NSIS post-install script
; Included by electron-builder via nsis.include config

!macro customInstall
  ; Create AppData directory for status files
  CreateDirectory "$APPDATA\NetSentinel"

  ; Run the WSL2 + Kali setup script in a visible PowerShell window
  ; so the user can see progress and respond to prompts
  DetailPrint "Running prerequisite setup (WSL2 + Kali Linux)..."
  ExecWait 'powershell.exe -ExecutionPolicy Bypass -File "$INSTDIR\resources\installer\setup.ps1" "$INSTDIR"'

  ; Write install path for the app to find
  FileOpen $0 "$APPDATA\NetSentinel\install-path.txt" w
  FileWrite $0 "$INSTDIR"
  FileClose $0
!macroend

!macro customUninstall
  ; Stop backend process if running
  ExecWait 'powershell.exe -Command "Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force"'
  ; Stop sandbox in Kali
  ExecWait 'wsl -d kali-linux -- bash -c "pkill -f netsentinel-sandbox 2>/dev/null || true"'
!macroend

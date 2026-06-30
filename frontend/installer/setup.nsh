!macro customInstall
  CreateDirectory "$APPDATA\NetSentinel"
  FileOpen $0 "$APPDATA\NetSentinel\install-path.txt" w
  FileWrite $0 "$INSTDIR"
  FileClose $0
!macroend

!macro customUninstall
  ExecWait 'powershell.exe -NoProfile -Command "Get-Process -Name sandbox -ErrorAction SilentlyContinue | Stop-Process -Force"'
!macroend

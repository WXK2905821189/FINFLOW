@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-finflow.ps1"
if errorlevel 1 (
  echo.
  echo FINFLOW shutdown failed. See the message above.
  pause
)
endlocal

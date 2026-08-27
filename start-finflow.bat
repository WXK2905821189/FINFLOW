@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-finflow.ps1"
if errorlevel 1 (
  echo.
  echo FINFLOW startup failed. See the message above.
  pause
)
endlocal

@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0docker-start.ps1" -Down
if errorlevel 1 (
  echo 停止失败，请查看上方错误。
  pause
  exit /b 1
)
pause

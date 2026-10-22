@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0docker-start.ps1" -NoBuild -OpenBrowser
if errorlevel 1 (
  echo 启动失败，请查看上方错误。首次构建或更新代码请运行 docker-start.ps1。
  pause
  exit /b 1
)
pause

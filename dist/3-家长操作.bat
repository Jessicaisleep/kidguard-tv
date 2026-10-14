@echo off
chcp 65001 >nul
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\kg.ps1" -Action unlock %*
echo.
pause

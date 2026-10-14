@echo off
chcp 65001 >nul
title KidGuard 电脑控制台（关掉本窗口 = 关闭控制台）
echo.
echo   正在启动 KidGuard 电脑控制台...
echo   稍后会自动打开浏览器页面；关掉这个黑窗口就等于关掉控制台。
echo   电视上的管控设置不受影响，关掉控制台也不会解除管控。
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\kgsrv.ps1" %*
echo.
echo   控制台已退出。如果上面有红色报错，请把这一屏截图发给开发者。
pause
